package demo.logscraper;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosisTest {
    private static final LocalDateTime T = LocalDateTime.of(2026, 11, 2, 21, 15, 0);
    private static final Map<String, String> ROLES = Map.of("app-01", "api-gateway", "app-02", "payment-service", "app-03", "core-banking-adapter");

    private static LogEntry e(String server, int ms, String level, String msg, String... extra) {
        return new LogEntry(server, T.plusNanos(ms * 1_000_000L), level, "t", "c.d.X", msg, List.of(extra));
    }

    @Test
    void namesWhereItBrokeAndRaisesMoneyAlert() {
        var timeline = List.of(
                e("app-01", 0, "INFO", "POST /api/v1/transfer trxId=TRX-88231"),
                e("app-02", 50, "INFO", "received trxId=TRX-88231"),
                e("app-03", 80, "INFO", "debit request trxId=TRX-88231"),
                e("app-02", 4200, "ERROR", "trxId=TRX-88231 FAILED after retry", "java.lang.IllegalStateException: x", "Caused by: java.net.http.HttpTimeoutException: request timed out"),
                e("app-03", 4300, "INFO", "trxId=TRX-88231 debit POSTED ref=CB1"),
                e("app-01", 4400, "WARN", "trxId=TRX-88231 status=FAILED httpStatus=504"),
                e("app-03", 6000, "INFO", "trxId=TRX-88231 debit POSTED ref=CB2"));
        var d = Diagnosis.of(timeline, ROLES, List.of("debit POSTED"), List.of("status=FAILED"));
        assertThat(d.verdict()).isEqualTo(Diagnosis.Verdict.GAGAL);
        assertThat(d.headline()).startsWith("Putus di app-02 (payment-service), 4,2 detik");
        assertThat(d.path()).containsExactly("app-01", "app-02", "app-03");
        assertThat(d.alert()).contains("terdebit 2 kali di app-03").contains("lebih dari sekali").contains("reversal");
        assertThat(d.details()).anyMatch(s -> s.contains("HttpTimeoutException"));
    }

    @Test
    void successHasNoAlert() {
        var d = Diagnosis.of(List.of(e("app-01", 0, "INFO", "start"), e("app-03", 300, "INFO", "debit POSTED")),
                ROLES, List.of("debit POSTED"), List.of("status=FAILED"));
        assertThat(d.verdict()).isEqualTo(Diagnosis.Verdict.BERHASIL);
        assertThat(d.alert()).isNull();
        assertThat(d.headline()).contains("0,3 detik");
    }
}
