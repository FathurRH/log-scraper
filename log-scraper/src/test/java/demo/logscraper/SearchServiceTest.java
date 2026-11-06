package demo.logscraper;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchServiceTest {
    @Test
    void tidiesWhatPeoplePasteFromChat() {
        assertThat(SearchService.normalize("  trx-88231). ")).isEqualTo("TRX-88231");
        assertThat(SearchService.normalize("\"TRX-88231\"")).isEqualTo("TRX-88231");
        assertThat(SearchService.normalize("core host slow")).isEqualTo("core host slow");
    }

    @Test
    void refusesEmptyShortLongAndUnsafeInput() {
        assertThatThrownBy(() -> SearchService.normalize("   ")).hasMessageContaining("Masukkan");
        assertThatThrownBy(() -> SearchService.normalize("ab")).hasMessageContaining("minimal 4");
        assertThatThrownBy(() -> SearchService.normalize("x".repeat(81))).hasMessageContaining("maksimal 80");
        assertThatThrownBy(() -> SearchService.normalize("TRX-1'; rm -rf / #")).hasMessageContaining("tidak didukung");
        assertThatThrownBy(() -> SearchService.normalize("TRX-1 $(reboot)")).hasMessageContaining("tidak didukung");
        assertThatThrownBy(() -> SearchService.normalize("TRX\n-1")).hasMessageContaining("satu baris");
    }

    @Test
    void quotesTheTermForTheRemoteShell() {
        assertThat(SearchService.grepCommand("TRX-88231", 40)).isEqualTo("LC_ALL=C grep -h -i -F -A 40 -- 'TRX-88231'");
    }

    @Test
    void typoFoldingFindsNearIds() {
        assertThat(SearchService.distance(SearchService.fold("TRX-8823l"), SearchService.fold("TRX-88231"))).isZero();
        assertThat(SearchService.distance(SearchService.fold("TRX-8823"), SearchService.fold("TRX-88231"))).isEqualTo(1);
    }
}
