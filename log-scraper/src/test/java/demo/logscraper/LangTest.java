package demo.logscraper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LangTest {
    @AfterEach
    void reset() { Lang.clear(); }

    @Test
    void picksEnglishOnlyWhenAsked() {
        assertThat(Lang.wantsEnglish(null, null)).isFalse();
        assertThat(Lang.wantsEnglish("id", null)).isFalse();
        assertThat(Lang.wantsEnglish("en", null)).isTrue();
        assertThat(Lang.wantsEnglish("id", "en")).isTrue();
        assertThat(Lang.wantsEnglish("EN-us", "")).isTrue();
    }

    @Test
    void diagnosisSpeaksEnglish() {
        Lang.set(true);
        LocalDateTime t = LocalDateTime.of(2026, 11, 8, 10, 0);
        var timeline = List.of(
                new LogEntry("app-01", t, "INFO", "t", "x", "start reqId=REQ-1", List.of()),
                new LogEntry("app-02", t.plusNanos(1_500_000_000L), "ERROR", "t", "x", "reqId=REQ-1 FAILED", List.of()),
                new LogEntry("app-03", t.plusNanos(1_600_000_000L), "INFO", "t", "x", "record COMMITTED", List.of()),
                new LogEntry("app-01", t.plusNanos(1_700_000_000L), "WARN", "t", "x", "status=FAILED", List.of()));
        var d = Diagnosis.of(timeline, Map.of("app-02", "process-service"), List.of("COMMITTED"), List.of("status=FAILED"));
        assertThat(d.headline()).startsWith("Broke at app-02 (process-service), 1.5 seconds after the request came in: FAILED");
        assertThat(d.alert()).startsWith("Data was already saved on app-03").contains("reported as FAILED");
        assertThat(d.details().getFirst()).startsWith("Went through 3 servers");
    }

    @Test
    void errorsFollowTheLanguage() {
        Lang.set(true);
        assertThatThrownBy(() -> SearchService.normalize("ab")).hasMessageStartingWith("Too short");
        Lang.set(false);
        assertThatThrownBy(() -> SearchService.normalize("ab")).hasMessageStartingWith("Terlalu pendek");
    }

    @Test
    void wrapCarriesTheLanguageToOtherThreads() throws Exception {
        Lang.set(true);
        var task = Lang.wrap(() -> Lang.t("ya", "yes"));
        Lang.clear();
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            assertThat(pool.submit(task).get()).isEqualTo("yes");
        }
    }
}
