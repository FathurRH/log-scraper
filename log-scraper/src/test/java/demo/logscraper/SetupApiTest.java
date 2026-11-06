package demo.logscraper;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.shell.ProcessShellFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The whole first-run setup against a real SSH server (Apache MINA sshd running the machine's own /bin/sh and grep),
 * with logs in folders and formats Log Scraper has never been told about.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SetupApiTest {
    private static final Path LAB;
    private static final String TOKEN = "tok-123";
    private static SshServer sshd;
    private static final AtomicReference<PublicKey> AUTHORIZED = new AtomicReference<>();

    static {
        try {
            LAB = Files.createTempDirectory("lab");
            Files.writeString(LAB.resolve("servers.json"), "[]");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("scraper.servers-file", () -> LAB.resolve("servers.json").toString());
        r.add("scraper.key-dir", () -> LAB.resolve("keys").toString());
        r.add("scraper.audit-file", () -> LAB.resolve("audit.log").toString());
        r.add("scraper.setup-token", () -> TOKEN);
    }

    @BeforeAll
    static void startServerAndLogs() throws Exception {
        String now = LocalDateTime.now().minusHours(1).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
        String dmy = LocalDateTime.now().minusHours(1).format(DateTimeFormatter.ofPattern("dd-MMM-yyyy", java.util.Locale.ENGLISH));
        write("opt/payment/logs/payment.json.log",
                "{\"@timestamp\":\"" + now + ".118+07:00\",\"level\":\"INFO\",\"logger\":\"pay\",\"message\":\"transfer request ref=TRX-55120 rekening=1234567890\"}\n"
                        + "{\"@timestamp\":\"" + now + ".415+07:00\",\"level\":\"ERROR\",\"logger\":\"pay\",\"message\":\"core debit timeout ref=TRX-55120\"}\n");
        write("u01/app/core/logs/core-adapter.log",
                "[" + now.replace('T', ' ') + ",455] [INFO ] [pool-2] CoreAdapter - debit POSTED ref=TRX-55120\n");
        write("srv/notif/log/catalina.out", dmy + " " + now.substring(11) + ".530 INFO [exec-1] id.Sms.send SMS gagal ref=TRX-55120\n");
        write("srv/odd/syslogstyle.log", "Nov  6 09:14:07 host odd[12]: TRX-55120 handled in a format nobody told us about\n");
        write("var/log/auth.log", now + " sshd TRX-55120 should stay hidden\n");

        sshd = SshServer.setUpDefaultServer();
        sshd.setPort(0);
        sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(LAB.resolve("hostkey.ser")));
        sshd.setPasswordAuthenticator((u, p, s) -> u.equals("logreader") && p.equals("bacasaja"));
        sshd.setPublickeyAuthenticator((u, key, s) -> u.equals("logreader") && AUTHORIZED.get() != null && KeyUtils.compareKeys(AUTHORIZED.get(), key));
        sshd.setCommandFactory((ch, cmd) -> new ProcessShellFactory(cmd, "/bin/sh", "-c", cmd).createShell(ch));
        sshd.start();
    }

    @AfterAll
    static void stop() throws Exception {
        sshd.stop(true);
    }

    private static void write(String rel, String text) throws Exception {
        Path p = LAB.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    private static String lab(String rel) { return LAB.toString() + "/" + rel; }

    @Autowired
    MockMvc mvc;

    private MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder b) {
        return b.header("X-Setup-Token", TOKEN).contentType(MediaType.APPLICATION_JSON);
    }

    private String json(MockHttpServletRequestBuilder b, String body) throws Exception {
        MvcResult r = mvc.perform(withToken(b).content(body)).andReturn();
        return r.getResponse().getContentAsString();
    }

    @Test
    @Order(1)
    void settingsNeedTheTokenAndNeverReturnThePassword() throws Exception {
        mvc.perform(get("/api/setup/servers")).andExpect(status().isUnauthorized());
        String put = json(put("/api/setup/servers/ubuntu-1"),
                "{\"role\":\"payment\",\"host\":\"127.0.0.1\",\"port\":" + sshd.getPort() + ",\"username\":\"logreader\",\"password\":\"bacasaja\","
                        + "\"logPaths\":[\"" + lab("opt/payment/logs/payment*.log") + "\"]}");
        assertThat(put).contains("\"hasPassword\":true").doesNotContain("bacasaja");
        String list = mvc.perform(get("/api/setup/servers").header("X-Setup-Token", TOKEN)).andReturn().getResponse().getContentAsString();
        assertThat(list).contains("ubuntu-1").doesNotContain("bacasaja");
        assertThat(Files.readString(LAB.resolve("servers.json"))).doesNotContain("bacasaja").contains("enc:v1:");
        assertThat(Files.readString(LAB.resolve("audit.log"))).doesNotContain("bacasaja");
    }

    @Test
    @Order(2)
    void badPathsAreRefusedBeforeTheyReachAShell() throws Exception {
        mvc.perform(withToken(put("/api/setup/servers/ubuntu-1")).content("{\"logPaths\":[\"/var/log/a; rm -rf /\"]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(3)
    void testConnectionShowsEachStepAndTheFingerprint() throws Exception {
        String body = json(post("/api/setup/servers/ubuntu-1/test"), "");
        assertThat(body).contains("\"ok\":true").contains("Jaringan").contains("Identitas server").contains("SHA256:").contains("grep tersedia");
        // a wrong password fails at the login step, with a hint, and nothing else is tried
        String bad = json(post("/api/setup/servers/ubuntu-1/test"), "{\"password\":\"salah\"}");
        assertThat(bad).contains("\"ok\":false").contains("Dilewati: login belum berhasil").contains("authorized_keys");
    }

    @Test
    @Order(4)
    void aPinnedFingerprintThatDoesNotMatchStopsTheConnection() throws Exception {
        String fake = "SHA256:" + "A".repeat(43);
        String body = json(post("/api/setup/servers/ubuntu-1/test"), "{\"hostKeyFingerprint\":\"" + fake + "\"}");
        assertThat(body).contains("\"ok\":false").contains("BERBEDA");
        // the real fingerprint, taken from a first test, pins fine
        String ok = json(post("/api/setup/servers/ubuntu-1/test"), "");
        String fp = ok.replaceAll("(?s).*?(SHA256:[A-Za-z0-9+/]{43}).*", "$1");
        assertThat(json(put("/api/setup/servers/ubuntu-1"), "{\"hostKeyFingerprint\":\"" + fp + "\"}")).contains(fp);
        assertThat(json(post("/api/setup/servers/ubuntu-1/test"), "")).contains("cocok dengan yang disimpan").contains("\"ok\":true");
    }

    @Test
    @Order(5)
    void findsLogFoldersFromAnIdThePersonKnowsAndHidesSystemLogs() throws Exception {
        String body = json(post("/api/setup/servers/ubuntu-1/discover"),
                "{\"id\":\"trx-55120\",\"roots\":[\"" + lab("opt") + "\",\"" + lab("u01") + "\",\"" + lab("srv") + "\",\"" + lab("var") + "\"]}");
        assertThat(body).contains(lab("opt/payment/logs/payment*.log"), lab("u01/app/core/logs/core-adapter*.log"),
                lab("srv/notif/log/catalina*.out"), "\"hiddenSystemLogs\":1");
        assertThat(body).doesNotContain("auth.log");
    }

    @Test
    @Order(6)
    void checkAndPreviewShowTheFormatBeforeTheFirstSearch() throws Exception {
        String chk = json(post("/api/setup/servers/ubuntu-1/check"), "{\"path\":\"" + lab("opt/payment/logs/payment*.log") + "\"}");
        assertThat(chk).contains("\"count\":1");
        String miss = json(post("/api/setup/servers/ubuntu-1/check"), "{\"path\":\"" + lab("opt/nothing/*.log") + "\"}");
        assertThat(miss).contains("\"count\":0");
        String pv = json(post("/api/setup/servers/ubuntu-1/preview"), "{\"path\":\"" + lab("opt/payment/logs/payment.json.log") + "\"}");
        assertThat(pv).contains("\"ok\":true").contains("\"percent\":100").contains("JSON satu baris");
        assertThat(pv).doesNotContain("1234567890"); // account number is masked in the preview
        String odd = json(post("/api/setup/servers/ubuntu-1/preview"), "{\"path\":\"" + lab("srv/odd/syslogstyle.log") + "\"}");
        assertThat(odd).contains("\"ok\":false").contains("\"recognized\":0").contains("belum dikenali");
    }

    @Test
    @Order(7)
    void searchUsesTheNewSettingsWithoutARestartAndReportsUnknownFormats() throws Exception {
        json(put("/api/setup/servers/ubuntu-1"), "{\"logPaths\":[\"" + lab("opt/payment/logs/payment*.log") + "\",\"" + lab("u01/app/core/logs/core-adapter.log")
                + "\",\"" + lab("srv/notif/log/catalina.out") + "\"]}");
        String body = mvc.perform(get("/api/search").param("q", "TRX-55120")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("\"total\":4").contains("core debit timeout").contains("debit POSTED").contains("SMS gagal");
        assertThat(body).doesNotContain("1234567890");

        json(put("/api/setup/servers/ubuntu-odd"), "{\"host\":\"127.0.0.1\",\"port\":" + sshd.getPort() + ",\"username\":\"logreader\",\"password\":\"bacasaja\","
                + "\"logPaths\":[\"" + lab("srv/odd/syslogstyle.log") + "\"]}");
        String odd = mvc.perform(get("/api/search").param("q", "TRX-55120")).andReturn().getResponse().getContentAsString();
        assertThat(odd).contains("FORMAT_TIDAK_DIKENALI").contains("formatnya belum dikenali");
    }

    @Test
    @Order(8)
    void aGeneratedKeyLogsInAfterTheAdminAuthorizesIt() throws Exception {
        String key = json(post("/api/setup/servers/ubuntu-1/key"), "");
        assertThat(key).contains("ecdsa-sha2-nistp256").doesNotContain("PRIVATE");
        String pub = key.replaceAll("(?s).*\"publicKey\":\"([^\"]+)\".*", "$1");
        // before the admin installs the public key, a key-only login is refused
        String before = json(post("/api/setup/servers/ubuntu-1/test"), "{\"password\":\"\"}");
        assertThat(before).contains("\"ok\":false");
        AUTHORIZED.set(PublicKeyEntry.parsePublicKeyEntry(pub).resolvePublicKey(null, null, null));
        String after = json(post("/api/setup/servers/ubuntu-1/test"), "{\"password\":\"\"}");
        assertThat(after).contains("\"ok\":true");
        assertThat(Files.isReadable(LAB.resolve("keys/ubuntu-1.key"))).isTrue();
    }
}
