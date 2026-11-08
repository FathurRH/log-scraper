package demo.logscraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns raw "grep -A N" output into log entries. A line that starts with a timestamp opens a new entry, which is kept only
 * if it mentions the search term. Lines without a timestamp (stack traces, "Caused by") stay with the entry above them,
 * so an exception is never split from the line that caused it.
 *
 * Five line formats are understood (see {@link Format}). A line that mentions the search term but fits none of them is
 * counted as "unrecognized" instead of being dropped silently, so a format problem never looks like "ID not found".
 */
public final class LogParser {
    public enum Format {
        SPRING("Spring Boot / Logback", "Spring Boot / Logback"),
        ISO("Tanggal ISO dengan huruf T", "ISO date with a T"),
        JSON("JSON satu baris per log", "JSON, one line per entry"),
        BRACKET("Log4j dengan kurung siku", "Log4j with square brackets"),
        TOMCAT("Tomcat / WebLogic", "Tomcat / WebLogic");

        private final String label, labelEn;

        Format(String label, String labelEn) { this.label = label; this.labelEn = labelEn; }

        public String label() { return Lang.t(label, labelEn); }
    }

    /** The first line of one log event, before any stack-trace lines are attached. */
    public record Head(Format format, LocalDateTime time, String level, String thread, String logger, String message,
                       List<String> extra) {}

    public record Result(List<LogEntry> entries, int unrecognized, List<String> samples) {}

    /** What a sample of lines looks like: how many lines fit each format, and the lines that fit none. */
    public record Detection(Map<Format, Integer> counts, int lines, int recognized, int unrecognized, Format best,
                            List<String> samples, List<Head> heads) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter TOMCAT_DATE = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);

    private static final String LEVEL = "(TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL)";
    // after the timestamp: level, optional "12345 ---" and [app] of Spring Boot 3, optional [thread], logger, ':' and message
    private static final String TAIL = "\\s+" + LEVEL
            + "\\s+(?:\\d+\\s+---\\s+(?:\\[[^\\]]*\\]\\s+)?)?(?:\\[([^\\]]*)\\]\\s+)?(\\S+)?\\s*:?\\s?(.*)$";
    private static final Pattern SPRING = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?)" + TAIL);
    private static final Pattern ISO = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?)(?:Z|[+-]\\d{2}:?\\d{2})?" + TAIL);
    private static final Pattern BRACKET = Pattern.compile(
            "^\\[(\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?)\\]\\s*\\[\\s*" + LEVEL
                    + "\\s*\\]\\s*(?:\\[([^\\]]*)\\]\\s*)?(?:(\\S+)\\s+-\\s+)?(.*)$");
    private static final Pattern TOMCAT = Pattern.compile(
            "^(\\d{2}-[A-Za-z]{3}-\\d{4}) (\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?)\\s+"
                    + "(SEVERE|WARNING|INFO|CONFIG|FINE|FINER|FINEST|ERROR|WARN|DEBUG)\\s+\\[([^\\]]*)\\]\\s+(\\S+)\\s+(.*)$");
    private static final Pattern JSON_TIME = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?");

    private static final String[] TIME_KEYS = {"@timestamp", "timestamp", "time", "ts", "asctime", "@t", "datetime"};
    private static final String[] LEVEL_KEYS = {"level", "severity", "levelname", "@l"};
    private static final String[] MESSAGE_KEYS = {"message", "msg", "@m", "text"};
    private static final String[] THREAD_KEYS = {"thread", "thread_name"};
    private static final String[] LOGGER_KEYS = {"logger", "logger_name", "name"};
    private static final String[] STACK_KEYS = {"stack_trace", "exception", "stack", "stacktrace"};
    private static final Set<String> KNOWN_KEYS = Set.of("@timestamp", "timestamp", "time", "ts", "asctime", "@t", "datetime",
            "level", "severity", "levelname", "@l", "message", "msg", "@m", "text", "thread", "thread_name", "logger",
            "logger_name", "name", "stack_trace", "exception", "stack", "stacktrace");

    public static List<LogEntry> parse(String server, String grepOutput, String term) {
        return parseDetailed(server, grepOutput, term).entries();
    }

    public static Result parseDetailed(String server, String grepOutput, String term) {
        List<LogEntry> out = new ArrayList<>();
        List<String> samples = new ArrayList<>();
        int unrecognized = 0;
        String needle = term.toLowerCase(Locale.ROOT);
        Pending cur = null;
        for (String line : grepOutput.split("\\R")) {
            if (line.equals("--")) { cur = flush(out, cur, server); continue; }
            Head h = head(line);
            if (h != null) {
                cur = flush(out, cur, server);
                if (line.toLowerCase(Locale.ROOT).contains(needle)) cur = new Pending(h);
            } else if (cur != null && !line.isBlank()) {
                cur.extra.add(line);
            } else if (!line.isBlank() && !line.startsWith("Binary file ") && line.toLowerCase(Locale.ROOT).contains(needle)) {
                unrecognized++;
                if (samples.size() < 3) samples.add(clip(line));
            }
        }
        flush(out, cur, server);
        return new Result(out, unrecognized, samples);
    }

    /** Reads a sample of lines (for example the first 20 of a file) and says which formats they fit. */
    public static Detection detect(String text) {
        Map<Format, Integer> counts = new EnumMap<>(Format.class);
        List<Head> heads = new ArrayList<>();
        List<String> samples = new ArrayList<>();
        int lines = 0, recognized = 0, unrecognized = 0;
        boolean afterHead = false;
        for (String line : text.split("\\R")) {
            if (line.isBlank() || line.equals("--")) continue;
            lines++;
            Head h = head(line);
            if (h != null) {
                recognized++;
                counts.merge(h.format(), 1, Integer::sum);
                heads.add(h);
                afterHead = true;
            } else if (!afterHead) {
                unrecognized++;
                if (samples.size() < 3) samples.add(clip(line));
            }
        }
        Format best = counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        return new Detection(counts, lines, recognized, unrecognized, best, samples, heads);
    }

    /** Reads the first line of a log event in any known format, or returns null when the line fits none of them. */
    public static Head head(String line) {
        if (line == null || line.isEmpty()) return null;
        try {
            char c = line.charAt(0);
            if (c == '{') return json(line);
            if (c == '[') return fromMatcher(Format.BRACKET, BRACKET.matcher(line));
            if (!Character.isDigit(c)) return null;
            Head h = fromMatcher(Format.SPRING, SPRING.matcher(line));
            if (h == null) h = fromMatcher(Format.ISO, ISO.matcher(line));
            if (h == null) h = tomcat(line);
            return h;
        } catch (RuntimeException e) {
            return null; // an impossible date such as month 13 is simply not a log line
        }
    }

    private static Head fromMatcher(Format f, Matcher m) {
        if (!m.matches()) return null;
        return new Head(f, ts(m.group(1)), level(m.group(2)), nz(m.group(3)), nz(m.group(4)), m.group(5), List.of());
    }

    private static Head tomcat(String line) {
        Matcher m = TOMCAT.matcher(line);
        if (!m.matches()) return null;
        LocalDate d = LocalDate.parse(m.group(1), TOMCAT_DATE);
        return new Head(Format.TOMCAT, ts(d + " " + m.group(2)), level(m.group(3)), nz(m.group(4)), nz(m.group(5)), m.group(6), List.of());
    }

    private static Head json(String line) {
        String t = line.strip();
        if (!t.endsWith("}")) return null;
        JsonNode n;
        try {
            n = MAPPER.readTree(t);
        } catch (Exception e) {
            return null;
        }
        if (n == null || !n.isObject()) return null;
        LocalDateTime when = null;
        for (String k : TIME_KEYS) {
            JsonNode v = n.get(k);
            if (v != null && (when = jsonTime(v)) != null) break;
        }
        if (when == null) return null;
        String message = text(n, MESSAGE_KEYS), lvl = text(n, LEVEL_KEYS);
        if (message == null && lvl == null) return null;
        StringBuilder msg = new StringBuilder(message == null ? "" : message);
        List<String> extra = new ArrayList<>();
        for (String k : STACK_KEYS) {
            JsonNode v = n.get(k);
            if (v != null && v.isTextual()) for (String l : v.asText().split("\\R")) if (!l.isBlank()) extra.add(l);
        }
        // other scalar fields (MDC values such as ref or status) are kept as key=value so the search term and the rules still see them
        for (Map.Entry<String, JsonNode> f : n.properties()) {
            JsonNode v = f.getValue();
            if (KNOWN_KEYS.contains(f.getKey()) || !v.isValueNode() || v.isNull()) continue;
            if (msg.length() > 0) msg.append(' ');
            msg.append(f.getKey()).append('=').append(v.asText());
        }
        return new Head(Format.JSON, when, level(lvl), nz(text(n, THREAD_KEYS)), nz(text(n, LOGGER_KEYS)), msg.toString(), extra);
    }

    private static LocalDateTime jsonTime(JsonNode v) {
        try {
            if (v.isNumber()) {
                long x = v.asLong();
                Instant i = x > 100_000_000_000L ? Instant.ofEpochMilli(x) : Instant.ofEpochSecond(x);
                return LocalDateTime.ofInstant(i, ZoneId.systemDefault());
            }
            if (!v.isTextual()) return null;
            Matcher m = JSON_TIME.matcher(v.asText().strip());
            return m.find() ? ts(m.group()) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String text(JsonNode n, String... keys) {
        for (String k : keys) {
            JsonNode v = n.get(k);
            if (v != null && v.isValueNode() && !v.isNull() && !v.asText().isBlank()) return v.asText();
        }
        return null;
    }

    /** "2026-11-06 09:14:02,455" or "2026-11-06T09:14:02.118" to a time, keeping the wall-clock time as written. */
    private static LocalDateTime ts(String s) {
        String t = s.replace('T', ' ').replace(',', '.');
        int dot = t.indexOf('.');
        String base = dot < 0 ? t : t.substring(0, dot);
        long millis = dot < 0 ? 0 : Long.parseLong((t.substring(dot + 1) + "000").substring(0, 3));
        return LocalDateTime.parse(base, DT).plusNanos(millis * 1_000_000L);
    }

    static String level(String s) {
        if (s == null) return "INFO";
        return switch (s.strip().toUpperCase(Locale.ROOT)) {
            case "TRACE" -> "TRACE";
            case "DEBUG", "CONFIG", "FINE", "FINER", "FINEST" -> "DEBUG";
            case "WARN", "WARNING" -> "WARN";
            case "ERROR", "FATAL", "SEVERE", "CRITICAL", "ERR" -> "ERROR";
            default -> "INFO";
        };
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String clip(String s) { return s.length() > 300 ? s.substring(0, 300) + "..." : s; }

    private static Pending flush(List<LogEntry> out, Pending p, String server) {
        if (p != null) {
            Head h = p.head;
            out.add(new LogEntry(server, h.time(), h.level(), h.thread(), h.logger(), h.message(), List.copyOf(p.extra)));
        }
        return null;
    }

    private static final class Pending {
        final Head head;
        final List<String> extra;

        Pending(Head head) {
            this.head = head;
            this.extra = new ArrayList<>(head.extra());
        }
    }

    private LogParser() {}
}
