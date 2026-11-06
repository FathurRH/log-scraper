package demo.logscraper;

import java.time.Duration;
import java.util.*;

/**
 * Reads a merged timeline and says, in one Bahasa sentence, how the transaction ended and where it broke.
 * Pure rules over the log lines, no AI at runtime.
 */
public final class Diagnosis {
    public enum Verdict { GAGAL, BERHASIL, TIDAK_JELAS }

    public record Result(Verdict verdict, String headline, List<String> details, List<String> path, long spanMillis,
                         String alert) {}

    public static Result of(List<LogEntry> timeline, Map<String, String> roles, List<String> moneyMoved, List<String> failed) {
        if (timeline.isEmpty()) return new Result(Verdict.TIDAK_JELAS, "Tidak ada log untuk ID ini.", List.of(), List.of(), 0, null);
        LogEntry first = timeline.getFirst(), last = timeline.getLast();
        List<String> path = timeline.stream().map(LogEntry::server).distinct().toList();
        long span = Duration.between(first.time(), last.time()).toMillis();

        Optional<LogEntry> firstError = timeline.stream().filter(LogEntry::isError).findFirst();
        Optional<LogEntry> reportedFailed = timeline.stream().filter(e -> containsAny(e.message(), failed)).findFirst();
        Optional<LogEntry> moved = timeline.stream().filter(e -> containsAny(e.message(), moneyMoved)).findFirst();
        Optional<LogEntry> problem = firstError
                .or(() -> timeline.stream().filter(LogEntry::isWarn).filter(e -> looksLikeFailure(e.message())).findFirst())
                .or(() -> reportedFailed);

        List<String> details = new ArrayList<>();
        details.add("Melewati " + path.size() + " server: " + String.join(" → ", path.stream().map(s -> label(s, roles)).toList()) + ".");
        String alert = null;
        long movedCount = timeline.stream().filter(e -> containsAny(e.message(), moneyMoved)).count();
        if (reportedFailed.isPresent() && moved.isPresent()) {
            alert = "Dana sudah terdebit " + (movedCount > 1 ? movedCount + " kali " : "") + "di " + label(moved.get().server(), roles)
                    + ", tetapi transaksi dilaporkan GAGAL ke nasabah. Kemungkinan saldo nasabah terpotong"
                    + (movedCount > 1 ? " lebih dari sekali" : "") + " tanpa transfer: cek dan lakukan reversal.";
        } else if (movedCount > 1) {
            alert = "Dana terdebit " + movedCount + " kali di " + label(moved.get().server(), roles) + " untuk satu transaksi. Cek kemungkinan debit ganda.";
        }
        if (problem.isPresent()) {
            LogEntry p = problem.get();
            String reason = shortReason(p);
            details.add("Masalah pertama: " + p.level() + " di " + label(p.server(), roles)
                    + (p.logger().isBlank() ? "" : ", " + p.logger()) + ".");
            p.extra().stream().filter(l -> l.startsWith("Caused by")).findFirst()
                    .ifPresent(c -> details.add("Penyebab (dilaporkan " + p.server() + "): " + c.replaceFirst("^Caused by: ", "")));
            return new Result(Verdict.GAGAL, "Putus di " + label(p.server(), roles) + ", " + after(first, p)
                    + " setelah request masuk: " + reason, details, path, span, alert);
        }
        return new Result(Verdict.BERHASIL, "Selesai normal di " + label(last.server(), roles) + " dalam " + seconds(span) + ".",
                details, path, span, alert);
    }

    /** For free-text searches the lines belong to many transactions, so only count them. */
    public static Result freeText(List<LogEntry> timeline, String q) {
        List<String> path = timeline.stream().map(LogEntry::server).distinct().toList();
        long errors = timeline.stream().filter(LogEntry::isError).count();
        String head = timeline.isEmpty() ? "Tidak ada log untuk kata ini."
                : "Kata \"" + q + "\" muncul di " + timeline.size() + " baris dari " + path.size() + " server"
                  + (errors > 0 ? ", " + errors + " di antaranya ERROR." : ".");
        return new Result(Verdict.TIDAK_JELAS, head, List.of("Cari satu ID transaksi untuk melihat di mana sebuah transaksi putus."),
                path, 0, null);
    }

    private static String shortReason(LogEntry e) {
        String m = e.message().replaceAll("trxId=\\S+\\s*", "").trim();
        return m.length() > 120 ? m.substring(0, 117) + "..." : m;
    }

    private static boolean looksLikeFailure(String m) {
        String s = m.toLowerCase(Locale.ROOT);
        return s.contains("fail") || s.contains("timeout") || s.contains("insufficient") || s.contains("rejected") || s.contains("gagal");
    }

    private static boolean containsAny(String s, List<String> needles) {
        return needles.stream().anyMatch(s::contains);
    }

    static String label(String server, Map<String, String> roles) {
        String role = roles.get(server);
        return role == null || role.isBlank() ? server : server + " (" + role + ")";
    }

    private static String after(LogEntry first, LogEntry e) {
        return seconds(Duration.between(first.time(), e.time()).toMillis());
    }

    static String seconds(long ms) {
        return String.format(Locale.of("id", "ID"), "%.1f detik", ms / 1000.0);
    }

    private Diagnosis() {}
}
