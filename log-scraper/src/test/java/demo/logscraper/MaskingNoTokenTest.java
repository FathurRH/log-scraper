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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** With no admin token set, masking can be made stricter but never weaker. */
@SpringBootTest
@AutoConfigureMockMvc
class MaskingNoTokenTest {
    private static final Path LAB;

    static {
        try { LAB = Files.createTempDirectory("masknt"); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("scraper.servers-file", () -> LAB.resolve("servers.json").toString());
        r.add("scraper.key-dir", () -> LAB.resolve("keys").toString());
        r.add("scraper.audit-file", () -> LAB.resolve("audit.log").toString());
        r.add("scraper.masking-file", () -> LAB.resolve("masking.json").toString());
        r.add("scraper.setup-token", () -> "");
    }

    @Autowired MockMvc mvc;

    @Test
    void cannotWeakenButCanStrengthen() throws Exception {
        mvc.perform(put("/api/setup/masking").contentType(MediaType.APPLICATION_JSON).content("{\"disabled\":[\"NIK\"]}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/setup/masking").contentType(MediaType.APPLICATION_JSON).content("{\"fields\":[\"polis\"]}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/setup/masking").contentType(MediaType.APPLICATION_JSON).content("{\"fields\":[]}"))
                .andExpect(status().isForbidden());
    }
}
