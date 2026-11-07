package demo.logscraper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SetupTest {
    private static ScraperProperties props(Path dir) {
        return new ScraperProperties(List.of(), 5, 15, 500, 40, 3, List.of(), List.of(), List.of(),
                dir.resolve("audit.log").toString(), dir.resolve("servers.json").toString(), dir.resolve("keys").toString(),
                dir.resolve("masking.json").toString(), "", List.of("/var/log"), Masker.Rules.DEFAULT);
    }

    @Test
    void foldsRotatedFilesIntoOnePattern() {
        assertThat(SetupService.patternFor("/opt/payment/logs/payment.json.log")).isEqualTo("/opt/payment/logs/payment*.log");
        assertThat(SetupService.patternFor("/opt/payment/logs/payment.json.log.1.gz")).isEqualTo("/opt/payment/logs/payment*.log");
        assertThat(SetupService.patternFor("/srv/notif/log/catalina.out")).isEqualTo("/srv/notif/log/catalina*.out");
        assertThat(SetupService.patternFor("/u01/app/core/logs/core-adapter.2026-11-05.log")).isEqualTo("/u01/app/core/logs/core-adapter*.log");
        assertThat(SetupService.patternFor("/data/trace")).isEqualTo("/data/trace");
    }

    @Test
    void refusesPathsThatCouldReachTheShell() {
        ServerStore.validatePath("/var/log/app/*.log");
        for (String bad : List.of("var/log", "/var/log/a b", "/var/log/$(reboot)", "/var/log/a;rm -rf /", "/var/log/'x'", "/var/log/../etc/passwd",
                "/var/log/a|b", "/var/log/`id`", "/var/log/a>b", ""))
            assertThatThrownBy(() -> ServerStore.validatePath(bad)).as(bad).isInstanceOf(ApiException.class);
    }

    @Test
    void mergeKeepsSavedValuesAndClearsOnEmptyString() {
        var old = new ScraperProperties.Server("app-01", "api", "10.0.0.1", 22, "logreader", "rahasia", null, null, List.of("/var/log/app/*.log"));
        var m = ServerStore.merge(old, "app-01", new ServerInput(null, null, null, null, null, null, null, List.of("/opt/x/*.log")));
        assertThat(m.password()).isEqualTo("rahasia");
        assertThat(m.logPaths()).containsExactly("/opt/x/*.log");
        assertThat(ServerStore.merge(old, "app-01", new ServerInput(null, null, null, null, "", null, null, null)).password()).isNull();
    }

    @Test
    void passwordsAreNeverPrintedAndNeverReturned() {
        var s = new ScraperProperties.Server("app-01", "api", "10.0.0.1", 22, "logreader", "rahasia123", null, null, List.of());
        assertThat(s.toString()).doesNotContain("rahasia123");
        assertThat(ServerView.of(s).toString()).doesNotContain("rahasia123");
        assertThat(ServerView.of(s).hasPassword()).isTrue();
    }

    @Test
    void storeEncryptsPasswordsOnDiskAndSurvivesRestart(@TempDir Path dir) throws Exception {
        var store = new ServerStore(props(dir));
        store.save(new ScraperProperties.Server("app-01", "api", "10.0.0.1", 22, "logreader", "rahasia123", null, null, List.of("/var/log/app/*.log")));
        String disk = Files.readString(dir.resolve("servers.json"));
        assertThat(disk).doesNotContain("rahasia123").contains("enc:v1:");
        var again = new ServerStore(props(dir));
        assertThat(again.find("app-01")).isPresent();
        assertThat(again.find("app-01").get().password()).isEqualTo("rahasia123");
        assertThat(again.delete("app-01")).isTrue();
        assertThat(new ServerStore(props(dir)).all()).isEmpty();
    }

    @Test
    void secretBoxUsesFreshNoncesAndRejectsTampering() {
        var box = new SecretBox(new byte[32]);
        String a = box.seal("x"), b = box.seal("x");
        assertThat(a).isNotEqualTo(b);
        assertThat(box.open(a)).isEqualTo("x");
        assertThat(box.open("plain")).isEqualTo("plain");
        String broken = a.substring(0, a.length() - 6) + "AAAA==";
        assertThatThrownBy(() -> box.open(broken)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void generatesAKeyAndRefusesToOverwriteIt(@TempDir Path dir) throws Exception {
        var setup = new SetupService(props(dir), null, new MaskingStore(props(dir)));
        var k = setup.generateKey("app-01", false);
        assertThat(k.publicKey()).startsWith("ecdsa-sha2-nistp256 ");
        assertThat(k.authorizedKeysLine()).startsWith("restrict ecdsa-sha2-nistp256 ");
        assertThat(Files.readString(dir.resolve("keys/app-01.key"))).contains("BEGIN OPENSSH PRIVATE KEY");
        assertThat(k.toString()).doesNotContain("PRIVATE");
        assertThatThrownBy(() -> setup.generateKey("app-01", false)).isInstanceOf(ApiException.class).hasMessageContaining("sudah ada");
        assertThat(setup.generateKey("app-01", true).publicKey()).isNotEqualTo(k.publicKey());
    }
}
