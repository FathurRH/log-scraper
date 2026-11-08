package demo.logscraper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class SearchService {
    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9 _.:@/#=+-]+");
    private static final Pattern ID_LIKE = Pattern.compile("[A-Za-z]{2,6}-[A-Za-z0-9]{3,12}");
    private static final DateTimeFormatter AUDIT_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static class BadQuery extends RuntimeException {
        public BadQuery(String message) { super(message); }
    }

    public record Line(String time, String server, String level, String thread, String logger, String message, List<String> extra) {}
    public record ServerStatus(String server, String role, SshSearcher.Status status, int hits, long millis, String note,
                               int unrecognized, List<String> unrecognizedSamples) {}
    public record Answer(String query, String from, String to, List<String> notes, List<ServerStatus> servers,
                         List<Line> timeline, int total, boolean truncated, Map<Masker.Kind, Integer> masked,
                         Diagnosis.Result diagnosis, List<String> suggestions, long millis, int manualMinutes) {}

    private final ScraperProperties props;
    private final ServerStore store;
    private final SshSearcher ssh;
    private final MaskingStore masking;
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    public SearchService(ScraperProperties props, ServerStore store, SshSearcher ssh, MaskingStore masking) {
        this.props = props;
        this.masking = masking;
        this.store = store;
        this.ssh = ssh;
    }

    /** Tidies what people paste from WhatsApp or a ticket ("REQ-88231)." or "  req-88231 ") and refuses anything unsafe. */
    public static String normalize(String raw) {
        if (raw == null) throw new BadQuery("Masukkan ID request atau kata yang dicari.");
        String q = raw.strip().replaceAll("^[\"'`(\\[{<]+|[\"'`)\\]}>.,;:!?]+$", "").strip();
        if (q.isEmpty()) throw new BadQuery("Masukkan ID request atau kata yang dicari.");
        if (q.chars().anyMatch(c -> c < 32)) throw new BadQuery("Cari satu baris saja, tanpa enter atau tab.");
        if (q.length() < 4) throw new BadQuery("Terlalu pendek: minimal 4 karakter supaya hasilnya tidak ribuan baris.");
        if (q.length() > 80) throw new BadQuery("Terlalu panjang: maksimal 80 karakter.");
        if (!ALLOWED.matcher(q).matches()) {
            String bad = q.chars().filter(c -> !ALLOWED.matcher(String.valueOf((char) c)).matches())
                    .mapToObj(c -> String.valueOf((char) c)).distinct().collect(Collectors.joining(" "));
            throw new BadQuery("Karakter  " + bad + "  tidak didukung. Pakai huruf, angka dan tanda - _ . : @ /");
        }
        if (ID_LIKE.matcher(q).matches()) q = q.toUpperCase(Locale.ROOT);
        return q;
    }

    public Answer search(String rawQuery, LocalDateTime from, LocalDateTime to, String who) {
        long t0 = System.currentTimeMillis();
        String q = normalize(rawQuery);
        List<String> notes = new ArrayList<>();
        if (!q.equals(rawQuery == null ? null : rawQuery.strip())) notes.add("Dicari sebagai \"" + q + "\".");
        LocalDateTime now = LocalDateTime.now();
        if (to == null) to = now;
        if (from == null) from = to.minusHours(24);
        if (from.isAfter(to)) { var tmp = from; from = to; to = tmp; notes.add("Waktu mulai dan selesai tertukar, sudah dibalik."); }
        if (Duration.between(from, to).toDays() > 31) { from = to.minusDays(31); notes.add("Rentang dibatasi 31 hari terakhir."); }

        List<ScraperProperties.Server> servers = store.all();
        String cmd = grepCommand(q, props.contextLines());
        Duration timeout = Duration.ofSeconds(props.timeoutSeconds());
        Map<String, Future<SshSearcher.Outcome>> futures = new LinkedHashMap<>();
        for (var s : servers) futures.put(s.name(), pool.submit(() -> s.logPaths().isEmpty()
                ? new SshSearcher.Outcome(s.name(), SshSearcher.Status.BELUM_DIATUR, "", "Folder log belum diatur untuk server ini", 0)
                : ssh.run(s, cmd + " " + paths(s), timeout)));

        List<LogEntry> all = new ArrayList<>();
        List<ServerStatus> statuses = new ArrayList<>();
        Map<String, String> roles = servers.stream().collect(Collectors.toMap(ScraperProperties.Server::name,
                s -> s.role() == null ? "" : s.role(), (a, b) -> a, LinkedHashMap::new));
        for (var s : servers) {
            SshSearcher.Outcome o = get(futures.get(s.name()), s.name());
            LogParser.Result parsed = o.status() == SshSearcher.Status.OK
                    ? LogParser.parseDetailed(s.name(), o.stdout(), q) : new LogParser.Result(List.of(), 0, List.of());
            List<LogEntry> found = parsed.entries();
            final LocalDateTime f = from, t = to;
            List<LogEntry> inRange = found.stream().filter(e -> !e.time().isBefore(f) && !e.time().isAfter(t)).toList();
            SshSearcher.Status st = o.status() == SshSearcher.Status.OK && inRange.isEmpty() ? SshSearcher.Status.KOSONG : o.status();
            String note = o.note();
            // lines that mention the term but fit no known log format must not look like "not found"
            if (o.status() == SshSearcher.Status.OK && found.isEmpty() && parsed.unrecognized() > 0) {
                st = SshSearcher.Status.FORMAT_TIDAK_DIKENALI;
                note = parsed.unrecognized() + " baris memuat kata ini tetapi formatnya belum dikenali";
            }
            List<String> samples = parsed.samples().stream().map(x -> Masker.mask(x, masking.rules()).text()).toList();
            statuses.add(new ServerStatus(s.name(), roles.get(s.name()), st, inRange.size(), o.millis(), note,
                    parsed.unrecognized(), samples));
            all.addAll(inRange);
        }
        all.sort(Comparator.comparing(LogEntry::time));
        int total = all.size();
        boolean truncated = total > props.maxResults();
        List<LogEntry> shown = truncated ? all.subList(0, props.maxResults()) : all;
        if (truncated) notes.add("Menampilkan " + props.maxResults() + " dari " + total + " baris. Persempit rentang waktu atau kata kunci.");

        Map<Masker.Kind, Integer> masked = new EnumMap<>(Masker.Kind.class);
        List<Line> lines = new ArrayList<>();
        List<LogEntry> maskedEntries = new ArrayList<>();
        for (LogEntry e : shown) {
            Masker.Result m = Masker.mask(e.message(), masking.rules());
            m.counts().forEach((k, v) -> masked.merge(k, v, Integer::sum));
            List<String> extra = e.extra().stream().map(x -> {
                Masker.Result r = Masker.mask(x, masking.rules());
                r.counts().forEach((k, v) -> masked.merge(k, v, Integer::sum));
                return r.text();
            }).toList();
            lines.add(new Line(e.time().toString().replace('T', ' '), e.server(), e.level(), e.thread(), e.logger(), m.text(), extra));
            maskedEntries.add(new LogEntry(e.server(), e.time(), e.level(), e.thread(), e.logger(), m.text(), extra));
        }
        Diagnosis.Result diag = ID_LIKE.matcher(q).matches()
                ? Diagnosis.of(maskedEntries, roles, props.committedMarkers(), props.failedMarkers())
                : Diagnosis.freeText(maskedEntries, q);

        List<String> suggestions = List.of();
        boolean anyReached = statuses.stream().anyMatch(s -> s.status() == SshSearcher.Status.OK || s.status() == SshSearcher.Status.KOSONG);
        if (total == 0 && anyReached) suggestions = suggest(q, servers, timeout);

        long ms = System.currentTimeMillis() - t0;
        audit(who, q, total, ms);
        return new Answer(q, from.toString(), to.toString(), notes, statuses, lines, total, truncated, masked, diag,
                suggestions, ms, servers.size() * props.manualMinutesPerServer());
    }

    public Masker.Rules maskingRules() { return masking.rules(); }

    public List<String> sampleIds() { return props.sampleIds(); }

    /** Fixed-string, case-insensitive grep. The term is single-quoted, and normalize() has already refused quotes. */
    static String grepCommand(String q, int context) {
        return "LC_ALL=C grep -h -i -F -A " + context + " -- " + quote(q);
    }

    static String quote(String s) {
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }

    private static String paths(ScraperProperties.Server s) {
        return String.join(" ", s.logPaths());
    }

    /** "REQ-8823l" finds nothing; offer the real IDs that are one or two typos away. */
    private List<String> suggest(String q, List<ScraperProperties.Server> servers, Duration timeout) {
        if (!ID_LIKE.matcher(q).matches()) return List.of();
        String prefix = q.substring(0, q.indexOf('-'));
        String cmd = "LC_ALL=C grep -h -o -E " + quote(prefix + "-[A-Za-z0-9]{3,12}");
        servers = servers.stream().filter(x -> !x.logPaths().isEmpty()).toList();
        List<Future<SshSearcher.Outcome>> fs = servers.stream()
                .map(s -> pool.submit(() -> ssh.run(s, cmd + " " + paths(s) + " | sort -u | head -20000", timeout))).toList();
        Set<String> ids = new TreeSet<>();
        for (int i = 0; i < fs.size(); i++) {
            var o = get(fs.get(i), servers.get(i).name());
            if (o.status() == SshSearcher.Status.OK) ids.addAll(Arrays.asList(o.stdout().split("\\R")));
        }
        String nq = fold(q);
        return ids.stream().filter(id -> !id.isBlank())
                .map(id -> Map.entry(id, distance(nq, fold(id))))
                .filter(e -> e.getValue() <= 2)
                .sorted(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .limit(3).map(Map.Entry::getKey).toList();
    }

    /** Letters people confuse with digits: O/0, I/l/1, S/5. */
    static String fold(String s) {
        return s.toUpperCase(Locale.ROOT).replace('O', '0').replace('I', '1').replace('L', '1').replace('S', '5');
    }

    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++)
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    private SshSearcher.Outcome get(Future<SshSearcher.Outcome> f, String server) {
        try {
            return f.get(props.timeoutSeconds() * 3L, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            f.cancel(true);
            return new SshSearcher.Outcome(server, SshSearcher.Status.TIMEOUT, "", "Server tidak menjawab", props.timeoutSeconds() * 3000L);
        }
    }

    private void audit(String who, String q, int hits, long ms) {
        String line = LocalDateTime.now().format(AUDIT_TS) + " user=" + who + " query=\"" + q + "\" hits=" + hits + " ms=" + ms + "\n";
        try {
            Files.writeString(Path.of(props.auditFile()), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("could not write audit log: {}", e.getMessage());
        }
    }
}
