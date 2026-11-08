package demo.logscraper;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/**
 * The checks behind first-run setup, all read-only on the remote server: can we connect, where are the logs, do the
 * files match what the parser understands. Remote commands are fixed templates; the only variable parts are an
 * ID that passed {@link SearchService#normalize(String)} and paths that passed {@link ServerStore#validatePath(String)}.
 */
@Service
public class SetupService {
    private static final Logger log = LoggerFactory.getLogger(SetupService.class);
    private static final Set<String> SYSTEM_LOGS = Set.of("auth.log", "syslog", "secure", "messages", "kern.log", "wtmp",
            "btmp", "lastlog", "dmesg", "faillog", "cron", "boot.log");
    private static final Pattern COMPRESSED = Pattern.compile(".*\\.(gz|zip|bz2|xz|tgz)$");
    private static final DateTimeFormatter AUDIT_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public record Step(String step, boolean ok, String detail) {}

    public record TestResult(boolean ok, List<Step> steps, String fingerprint, String hint) {}

    public record FoundPattern(String pattern, int files, List<String> examples, int compressedRotated) {}

    public record DiscoverResult(SshSearcher.Status status, String note, List<FoundPattern> patterns, int filesFound,
                                 int hiddenSystemLogs, int unreadableFolders, List<String> searchedRoots) {}

    public record CheckResult(SshSearcher.Status status, int count, List<String> files, String note) {}

    public record PreviewRow(String time, String level, String thread, String logger, String message, String format) {}

    public record PreviewResult(SshSearcher.Status status, String note, int lines, int recognized, int unrecognized,
                                int percent, boolean ok, String format, Map<String, Integer> formats, List<PreviewRow> rows,
                                List<String> unrecognizedSamples, Map<Masker.Kind, Integer> masked, String advice) {}

    public record KeyResult(String keyFile, String publicKey, String authorizedKeysLine, String installCommand) {}

    private final ScraperProperties props;
    private final SshSearcher ssh;
    private final MaskingStore masking;

    public SetupService(ScraperProperties props, SshSearcher ssh, MaskingStore masking) {
        this.props = props;
        this.masking = masking;
        this.ssh = ssh;
    }

    // ---- step 1: access --------------------------------------------------------------------------------------

    public TestResult test(ScraperProperties.Server s) {
        List<Step> steps = new ArrayList<>();
        Duration timeout = Duration.ofSeconds(props.timeoutSeconds());
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(s.host(), s.port()), props.timeoutSeconds() * 1000);
            steps.add(new Step(Lang.t("Jaringan", "Network"), true, Lang.en() ? "Port " + s.port() + " on " + s.host() + " is open" : "Port " + s.port() + " di " + s.host() + " terbuka"));
        } catch (IOException e) {
            steps.add(new Step(Lang.t("Jaringan", "Network"), false, Lang.t("Tidak bisa membuka ", "Cannot open ") + s.host() + ":" + s.port() + " (" + e.getClass().getSimpleName() + ")"));
            for (String later : List.of(Lang.t("Identitas server", "Server identity"), "Login", Lang.t("Perintah baca", "Read command")))
                steps.add(new Step(later, false, Lang.t("Dilewati: server belum terjangkau", "Skipped: server not reachable yet")));
            return new TestResult(false, steps, null, hintFor(SshSearcher.Status.TIDAK_TERJANGKAU));
        }
        SshSearcher.Outcome o = ssh.run(s, "grep --version | head -n 1", timeout);
        String fp = o.fingerprint();
        boolean pinned = s.hostKeyFingerprint() != null;
        boolean keyOk = o.status() != SshSearcher.Status.KUNCI_BERUBAH;
        String keyDetail = fp == null ? Lang.t("Server tidak menyebutkan kunci", "The server did not present a key")
                : fp + (pinned ? (keyOk ? Lang.t(" (cocok dengan yang disimpan)", " (matches the saved one)") : Lang.t(" (BERBEDA dari yang disimpan)", " (DIFFERENT from the saved one)"))
                        : Lang.t(" (belum disimpan: isi hostKeyFingerprint untuk mengunci)", " (not saved: set hostKeyFingerprint to pin it)"));
        steps.add(new Step(Lang.t("Identitas server", "Server identity"), keyOk && fp != null, keyDetail));
        boolean loggedIn = o.status() == SshSearcher.Status.OK || o.status() == SshSearcher.Status.KOSONG
                || o.status() == SshSearcher.Status.DITOLAK;
        steps.add(new Step("Login", loggedIn, loggedIn ? Lang.t("Berhasil sebagai ", "Signed in as ") + s.username() : String.valueOf(o.note())));
        if (!loggedIn) {
            steps.add(new Step(Lang.t("Perintah baca", "Read command"), false, Lang.t("Dilewati: login belum berhasil", "Skipped: login has not worked yet")));
            return new TestResult(false, steps, fp, hintFor(o.status()));
        }
        boolean grepOk = o.status() == SshSearcher.Status.OK;
        String first = o.stdout().lines().findFirst().orElse("").strip();
        steps.add(new Step(Lang.t("Perintah baca", "Read command"), grepOk, grepOk ? Lang.t("grep tersedia: ", "grep available: ") + first : String.valueOf(o.note())));
        return new TestResult(grepOk, steps, fp, grepOk ? null : hintFor(o.status()));
    }

    static String hintFor(SshSearcher.Status st) {
        return switch (st) {
            case TIDAK_TERJANGKAU -> Lang.t("Cek host dan port, VPN dan firewall. Minta tim server menjalankan: nc -vz <host> <port>", "Check the host and port, VPN and firewall. Ask the server team to run: nc -vz <host> <port>");
            case KUNCI_BERUBAH -> Lang.t("Jangan lanjut dulu. Pastikan dengan tim server bahwa server memang dibangun ulang, lalu simpan sidik jari yang baru", "Stop here. Confirm with the server team that the server really was rebuilt, then save the new fingerprint");
            case LOGIN_GAGAL -> Lang.t("Cek username. Untuk kunci SSH: pastikan baris dari endpoint /key sudah ada di ~/.ssh/authorized_keys akun itu. Untuk password: pastikan login password diizinkan", "Check the username. For an SSH key: make sure the line from the /key endpoint is in that account's ~/.ssh/authorized_keys. For a password: make sure password login is allowed");
            case DITOLAK -> Lang.t("Akun read-only menolak perintah. Minta tim server mengizinkan grep (dan zgrep untuk file .gz)", "The read-only account refused the command. Ask the server team to allow grep (and zgrep for .gz files)");
            case TIMEOUT -> Lang.t("Server atau jaringan lambat. Coba lagi, atau naikkan scraper.timeout-seconds", "The server or network is slow. Try again, or raise scraper.timeout-seconds");
            default -> Lang.t("Lihat pesan di atas", "See the message above");
        };
    }

    // ---- step 2: find the logs -------------------------------------------------------------------------------

    /** Finds which files contain an ID the person knows happened, and folds rotated files into one pattern per log. */
    public DiscoverResult discover(ScraperProperties.Server s, String rawId, List<String> roots) {
        String id = SearchService.normalize(rawId);
        List<String> useRoots = roots == null || roots.isEmpty() ? props.discoveryRoots() : roots;
        useRoots.forEach(ServerStore::validatePath);
        String cmd = "LC_ALL=C grep -rlIF --include='*.log' --include='*.log.*' --include='*.out' --include='*.txt' -- "
                + SearchService.quote(id) + " " + String.join(" ", useRoots) + " | head -n 200";
        SshSearcher.Outcome o = ssh.run(s, cmd, Duration.ofSeconds(props.discoverySeconds()));
        int unreadable = countLines(o.note(), "Permission denied");
        if (o.status() != SshSearcher.Status.OK && o.status() != SshSearcher.Status.KOSONG)
            return new DiscoverResult(o.status(), o.note(), List.of(), 0, 0, 0, useRoots);
        Map<String, List<String>> live = new LinkedHashMap<>();
        Map<String, Integer> compressed = new LinkedHashMap<>();
        int hidden = 0, found = 0;
        for (String line : o.stdout().split("\\R")) {
            String path = line.strip();
            if (path.isEmpty() || !path.startsWith("/")) continue;
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (SYSTEM_LOGS.contains(name)) { hidden++; continue; }
            found++;
            String pattern = patternFor(path);
            live.computeIfAbsent(pattern, k -> new ArrayList<>());
            if (COMPRESSED.matcher(name).matches()) compressed.merge(pattern, 1, Integer::sum);
            else live.get(pattern).add(path);
        }
        List<FoundPattern> patterns = live.entrySet().stream()
                .map(e -> new FoundPattern(e.getKey(), e.getValue().size(), e.getValue().stream().limit(3).toList(),
                        compressed.getOrDefault(e.getKey(), 0)))
                .toList();
        String note = patterns.isEmpty()
                ? Lang.t("ID tidak ditemukan di folder yang dicari. Pastikan ID benar-benar terjadi di server ini, atau tambahkan folder lewat parameter roots", "The ID was not found in the folders searched. Make sure it really happened on this server, or add folders with the roots parameter")
                : null;
        return new DiscoverResult(patterns.isEmpty() ? SshSearcher.Status.KOSONG : SshSearcher.Status.OK, note, patterns, found,
                hidden, unreadable, useRoots);
    }

    /** app.json.log and app.json.log.1.gz both become /dir/app*.log. Files of other kinds keep their exact path. */
    static String patternFor(String path) {
        int slash = path.lastIndexOf('/');
        String dir = path.substring(0, slash), name = path.substring(slash + 1);
        String stem = name.split("\\.", 2)[0];
        if (stem.isEmpty() || stem.length() == name.length()) return path;
        if (name.contains(".log")) return dir + "/" + stem + "*.log";
        if (name.endsWith(".out")) return dir + "/" + stem + "*.out";
        if (name.endsWith(".txt")) return dir + "/" + stem + "*.txt";
        return path;
    }

    /** Which files a saved path or pattern really matches on the server. */
    public CheckResult check(ScraperProperties.Server s, String pattern) {
        ServerStore.validatePath(pattern);
        SshSearcher.Outcome o = ssh.run(s, "LC_ALL=C grep -l -e '' " + pattern + " | head -n 200", Duration.ofSeconds(props.timeoutSeconds()));
        if (o.status() == SshSearcher.Status.OK) {
            List<String> files = o.stdout().lines().map(String::strip).filter(x -> !x.isEmpty()).toList();
            return new CheckResult(SshSearcher.Status.OK, files.size(), files.stream().limit(10).toList(), o.note());
        }
        if (o.status() == SshSearcher.Status.KOSONG)
            return new CheckResult(SshSearcher.Status.KOSONG, 0, List.of(),
                    countLines(o.note(), "Permission denied") > 0 ? Lang.t("Folder atau file ada tetapi tidak boleh dibaca oleh akun ini", "The folder or file exists but this account may not read it")
                            : Lang.t("Tidak ada file yang cocok, atau semua file yang cocok masih kosong", "No matching files, or every matching file is still empty"));
        return new CheckResult(o.status(), 0, List.of(), o.note());
    }

    // ---- step 3: is the format understood --------------------------------------------------------------------

    /** Reads the first lines of a log, says how many the parser understands, and shows them masked. */
    public PreviewResult preview(ScraperProperties.Server s, String path, int lines) {
        ServerStore.validatePath(path);
        int n = Math.max(1, Math.min(lines, 50));
        SshSearcher.Outcome o = ssh.run(s, "head -q -n " + n + " " + path + " | head -n " + n, Duration.ofSeconds(props.timeoutSeconds()));
        if (o.status() != SshSearcher.Status.OK)
            return new PreviewResult(o.status(), o.status() == SshSearcher.Status.KOSONG ? Lang.t("File kosong atau tidak ditemukan", "File is empty or not found") : o.note(),
                    0, 0, 0, 0, false, null, Map.of(), List.of(), List.of(), Map.of(), null);
        LogParser.Detection d = LogParser.detect(o.stdout());
        Map<Masker.Kind, Integer> masked = new EnumMap<>(Masker.Kind.class);
        List<PreviewRow> rows = d.heads().stream().limit(20).map(h -> {
            Masker.Result m = Masker.mask(h.message(), masking.rules());
            m.counts().forEach((k, v) -> masked.merge(k, v, Integer::sum));
            return new PreviewRow(h.time().toString().replace('T', ' '), h.level(), h.thread(), h.logger(), clip(m.text()), h.format().name());
        }).toList();
        List<String> samples = d.samples().stream().map(x -> {
            Masker.Result m = Masker.mask(x, masking.rules());
            m.counts().forEach((k, v) -> masked.merge(k, v, Integer::sum));
            return m.text();
        }).toList();
        int percent = d.lines() == 0 ? 0 : Math.round(100f * d.recognized() / d.lines());
        boolean ok = d.lines() > 0 && percent >= 80;
        Map<String, Integer> formats = new LinkedHashMap<>();
        d.counts().forEach((k, v) -> formats.put(k.name(), v));
        String advice = ok ? null : d.recognized() == 0
                ? Lang.t("Format log ini belum dikenali, jadi pencarian akan menandainya sebagai \"format tidak dikenali\" dan bukan \"tidak ada\". ",
                         "This log format is not recognized yet, so search will mark it as \"format not recognized\" rather than \"not found\". ")
                  + Lang.t("Format yang didukung: ", "Supported formats: ") + Arrays.stream(LogParser.Format.values()).map(LogParser.Format::label).toList()
                : Lang.t("Sebagian baris tidak dikenali. Cek contoh baris di bawah", "Some lines were not recognized. See the sample lines below");
        return new PreviewResult(SshSearcher.Status.OK, o.note(), d.lines(), d.recognized(), d.lines() - d.recognized(), percent, ok,
                d.best() == null ? null : d.best().label(), formats, rows, samples, masked, advice);
    }

    // ---- SSH key ---------------------------------------------------------------------------------------------

    /** Makes an ECDSA key pair for one server in the key folder. Only the public half leaves this machine. */
    public KeyResult generateKey(String serverName, boolean force) {
        Path dir = Path.of(props.keyDir()).toAbsolutePath().normalize();
        String file = serverName + ".key";
        try {
            Files.createDirectories(dir);
            Path priv = dir.resolve(file), pub = dir.resolve(file + ".pub");
            if (Files.exists(priv) && !force)
                throw new ApiException(409, Lang.en() ? "A key for " + serverName + " already exists. Add ?force=true to create a new one (the old key stops working)" : "Kunci untuk " + serverName + " sudah ada. Tambahkan ?force=true untuk membuat yang baru (kunci lama tidak berlaku lagi)");
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(256);
            KeyPair kp = g.generateKeyPair();
            String publicKey = PublicKeyEntry.toString(kp.getPublic());
            Files.deleteIfExists(priv);
            try (OutputStream out = Files.newOutputStream(priv, StandardOpenOption.CREATE_NEW)) {
                try {
                    Files.setPosixFilePermissions(priv, PosixFilePermissions.fromString("rw-------"));
                } catch (UnsupportedOperationException ignored) {
                    // not a POSIX file system
                }
                OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(kp, "log-scraper-" + serverName, null, out);
            }
            String line = "restrict " + publicKey + " log-scraper-" + serverName;
            Files.writeString(pub, line + "\n", StandardCharsets.UTF_8);
            return new KeyResult(file, publicKey, line,
                    "mkdir -p ~/.ssh && chmod 700 ~/.ssh && echo '" + line + "' >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(500, Lang.t("Kunci tidak bisa dibuat: ", "The key could not be created: ") + e.getMessage());
        }
    }

    // ---- audit -----------------------------------------------------------------------------------------------

    public void audit(String who, String action) {
        String line = LocalDateTime.now().format(AUDIT_TS) + " user=" + who + " setup=\"" + action.replace("\"", "'") + "\"\n";
        try {
            Files.writeString(Path.of(props.auditFile()), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("could not write audit log: {}", e.getMessage());
        }
    }

    private static int countLines(String text, String needle) {
        if (text == null) return 0;
        return (int) text.lines().filter(l -> l.contains(needle)).count();
    }

    private static String clip(String s) { return s.length() > 200 ? s.substring(0, 200) + "..." : s; }

    static String fingerprintOf(java.security.PublicKey key) { return KeyUtils.getFingerPrint(key); }
}
