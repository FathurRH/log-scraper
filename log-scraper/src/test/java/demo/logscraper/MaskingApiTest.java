package demo.logscraper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Masking rules are edited through the settings API: guarded by the token, saved to disk, and tried before saving. */
@SpringBootTest
@AutoConfigureMockMvc
class MaskingApiTest {
    private static final Path LAB;
    private static final String TOKEN = "tok-123";

    static {
        try { LAB = Files.createTempDirectory("mask"); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("scraper.servers-file", () -> LAB.resolve("servers.json").toString());
        r.add("scraper.key-dir", () -> LAB.resolve("keys").toString());
        r.add("scraper.audit-file", () -> LAB.resolve("audit.log").toString());
        r.add("scraper.masking-file", () -> LAB.resolve("masking.json").toString());
        r.add("scraper.setup-token", () -> TOKEN);
    }

    @Autowired MockMvc mvc;
    @Autowired MaskingStore store;

    @Test
    void needsTheTokenToReadOrChange() throws Exception {
        mvc.perform(get("/api/setup/masking")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/setup/masking").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
    }

    @Test
    void changesApplyAtOnceAndSurviveRestart() throws Exception {
        String line = "{\"text\":\"mail=budi@bank.co.id acct=1 polis=POL-12345678\"}";
        mvc.perform(post("/api/setup/masking/test").header("X-Setup-Token", TOKEN).contentType(MediaType.APPLICATION_JSON).content(line))
                .andExpect(status().isOk()).andExpect(jsonPath("$.masked").value(org.hamcrest.Matchers.containsString("b***@bank.co.id")))
                .andExpect(jsonPath("$.masked").value(org.hamcrest.Matchers.containsString("POL-12345678")));

        String body = "{\"disabled\":[\"EMAIL\"],\"fields\":[\"polis\"],\"custom\":[]}";
        mvc.perform(put("/api/setup/masking").header("X-Setup-Token", TOKEN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        assertThat(Masker.mask("mail=budi@bank.co.id polis=POL-12345678", store.rules()).text())
                .contains("budi@bank.co.id").doesNotContain("POL-12345678");
        assertThat(Files.readString(LAB.resolve("masking.json"))).contains("EMAIL").contains("polis");
        assertThat(new MaskingStore(new ScraperProperties(null, 5, 15, 500, 40, 3, null, null, null, "a", "b", "c",
                LAB.resolve("masking.json").toString(), "", null, Masker.Rules.DEFAULT)).rules().disabled()).containsExactly(Masker.Kind.EMAIL);
        assertThat(Files.readString(LAB.resolve("audit.log"))).contains("ubah penyamaran").contains("DIKURANGI");

        mvc.perform(put("/api/setup/masking").header("X-Setup-Token", TOKEN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"disabled\":[],\"fields\":[],\"custom\":[]}")).andExpect(status().isOk());
    }

    @Test
    void rejectsBadInput() throws Exception {
        for (String bad : new String[] {"{\"disabled\":[\"NOPE\"]}", "{\"fields\":[\"a b\"]}",
                "{\"custom\":[{\"name\":\"x\",\"regex\":\"([\",\"keepHead\":0,\"keepTail\":0}]}"}) {
            mvc.perform(put("/api/setup/masking").header("X-Setup-Token", TOKEN).contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest());
        }
    }
}
