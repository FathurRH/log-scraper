package demo.logscraper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The servers Log Scraper searches, and where their logs are. They start from application.yml and, once a setting is
 * changed through the settings API, live in servers.json (owner-only file) so that a change survives a restart and takes
 * effect on the next search without one. Everything that reaches a remote shell is checked here first.
 */
@Component
public class ServerStore {
    private static final Logger log = LoggerFactory.getLogger(ServerStore.class);
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,39}");
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9._:-]{1,253}");
    private static final Pattern USER = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]{0,31}");
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,60}");
    private static final Pattern FINGERPRINT = Pattern.compile("SHA256:[A-Za-z0-9+/]{43}");
    /** Absolute path; only * and ? may expand in the remote shell. No spaces, quotes, $, ;, |, &, `, or "..". */
    private static final Pattern PATH = Pattern.compile("/[A-Za-z0-9_./*?+@%-]{1,300}");

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecretBox box;
    private volatile List<ScraperProperties.Server> servers;

    public ServerStore(ScraperProperties props) {
        this.file = Path.of(props.serversFile()).toAbsolutePath();
        this.box = SecretBox.open(Path.of(props.keyDir()).toAbsolutePath().normalize(), System.getenv("SETTINGS_KEY"));
        List<ScraperProperties.Server> initial = props.servers() == null ? List.of() : props.servers();
        if (Files.isReadable(file)) {
            try {
                initial = mapper.readValue(file.toFile(), new TypeReference<List<ScraperProperties.Server>>() {}).stream()
                        .map(x -> withPassword(x, box.open(x.password()))).toList();
                log.info("Pengaturan server dibaca dari {}", file);
            } catch (IOException e) {
                throw new IllegalStateException("File pengaturan server rusak (" + file + "): " + e.getMessage(), e);
            }
        }
        initial.forEach(ServerStore::validate); // a bad entry stops the app at startup instead of failing on the first search
        this.servers = List.copyOf(initial);
    }

    public List<ScraperProperties.Server> all() { return servers; }

    public Optional<ScraperProperties.Server> find(String name) {
        return servers.stream().filter(s -> s.name().equals(name)).findFirst();
    }

    public synchronized void save(ScraperProperties.Server s) {
        validate(s);
        List<ScraperProperties.Server> next = new ArrayList<>(servers);
        boolean replaced = false;
        for (int i = 0; i < next.size(); i++) {
            if (next.get(i).name().equals(s.name())) { next.set(i, s); replaced = true; }
        }
        if (!replaced) next.add(s);
        persist(next);
        servers = List.copyOf(next);
    }

    public synchronized boolean delete(String name) {
        List<ScraperProperties.Server> next = new ArrayList<>(servers);
        if (!next.removeIf(s -> s.name().equals(name))) return false;
        persist(next);
        servers = List.copyOf(next);
        return true;
    }

    /** Applies a request on top of the saved server (or on nothing, for a new one). Does not save and does not validate. */
    public static ScraperProperties.Server merge(ScraperProperties.Server old, String name, ServerInput in) {
        ServerInput i = in == null ? new ServerInput(null, null, null, null, null, null, null, null) : in;
        return new ScraperProperties.Server(name,
                i.role() != null ? i.role() : old == null ? null : old.role(),
                i.host() != null ? i.host().strip() : old == null ? null : old.host(),
                i.port() != null ? i.port() : old == null ? 22 : old.port(),
                i.username() != null ? i.username().strip() : old == null ? null : old.username(),
                i.password() != null ? (i.password().isEmpty() ? null : i.password()) : old == null ? null : old.password(),
                i.keyFile() != null ? (i.keyFile().isBlank() ? null : i.keyFile().strip()) : old == null ? null : old.keyFile(),
                i.hostKeyFingerprint() != null ? (i.hostKeyFingerprint().isBlank() ? null : i.hostKeyFingerprint().strip())
                        : old == null ? null : old.hostKeyFingerprint(),
                i.logPaths() != null ? i.logPaths().stream().map(String::strip).toList() : old == null ? List.of() : old.logPaths());
    }

    public static void validate(ScraperProperties.Server s) {
        if (s.name() == null || !NAME.matcher(s.name()).matches())
            throw bad(Lang.t("Nama server: 1 sampai 40 karakter, huruf, angka, titik, garis bawah atau minus.", "Server name: 1 to 40 characters, letters, digits, dot, underscore or minus."));
        if (s.role() != null && (s.role().length() > 60 || s.role().chars().anyMatch(c -> c < 32)))
            throw bad(Lang.t("Peran server terlalu panjang (maksimal 60 karakter).", "Server role is too long (at most 60 characters)."));
        if (s.host() == null || !HOST.matcher(s.host()).matches()) throw bad(Lang.t("Host tidak valid. Isi nama server atau alamat IP.", "Invalid host. Enter a server name or an IP address."));
        if (s.port() < 1 || s.port() > 65535) throw bad(Lang.t("Port harus antara 1 dan 65535.", "Port must be between 1 and 65535."));
        if (s.username() == null || !USER.matcher(s.username()).matches()) throw bad(Lang.t("Username tidak valid.", "Invalid username."));
        if (s.keyFile() != null && !KEY.matcher(s.keyFile()).matches())
            throw bad(Lang.t("Nama file kunci hanya huruf, angka, titik, garis bawah dan minus (file ada di folder kunci).", "Key file name: only letters, digits, dot, underscore and minus (the file lives in the key folder)."));
        if (s.hostKeyFingerprint() != null && !FINGERPRINT.matcher(s.hostKeyFingerprint()).matches())
            throw bad(Lang.t("Sidik jari server harus berbentuk SHA256:... seperti yang ditampilkan Tes koneksi.", "The server fingerprint must look like SHA256:..., as shown by Test connection."));
        if (s.logPaths().size() > 20) throw bad(Lang.t("Maksimal 20 folder atau pola log per server.", "At most 20 log folders or patterns per server."));
        for (String p : s.logPaths()) validatePath(p);
    }

    /** A log path or pattern that is safe to hand to a remote shell. */
    public static void validatePath(String p) {
        if (p == null || !PATH.matcher(p).matches() || p.contains(".."))
            throw bad(Lang.en()
                    ? "Log folder \"" + (p == null ? "" : p) + "\" is not valid. Use a full path starting with / "
                      + "(letters, digits and . _ - + @ %, * and ? for patterns), without spaces or quotes."
                    : "Folder log \"" + (p == null ? "" : p) + "\" tidak valid. Pakai path lengkap yang diawali / "
                      + "(huruf, angka dan . _ - + @ %, tanda * dan ? untuk pola), tanpa spasi atau tanda kutip.");
    }

    private static ScraperProperties.Server withPassword(ScraperProperties.Server x, String password) {
        return new ScraperProperties.Server(x.name(), x.role(), x.host(), x.port(), x.username(), password, x.keyFile(),
                x.hostKeyFingerprint(), x.logPaths());
    }

    private static ApiException bad(String message) { return new ApiException(400, message); }

    private void persist(List<ScraperProperties.Server> list) {
        try {
            Path dir = file.getParent();
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "servers", ".tmp");
            try {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // not a POSIX file system (Windows): the folder's own permissions apply
            }
            // passwords are written encrypted; in memory they stay readable because the SSH login needs them
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), list.stream().map(x -> withPassword(x, box.seal(x.password()))).toList());
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new ApiException(500, Lang.t("Pengaturan tidak bisa disimpan ke ", "Settings could not be saved to ") + file + ": " + e.getMessage());
        }
    }
}
