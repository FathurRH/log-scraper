package demo.logscraper;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The masking rules in force. They start from application.yml; once changed through the settings API they live in
 * masking.json and apply to the next search without a restart. Only the settings API writes them (admin token), never
 * the search page.
 */
@Component
public class MaskingStore {
    private static final Logger log = LoggerFactory.getLogger(MaskingStore.class);
    static final int MAX_FIELDS = 50, MAX_CUSTOM = 20, MAX_REGEX = 200;

    /** What the settings API reads and writes. Kinds are given by name so a typo is a clear error, not a silent skip. */
    public record Config(List<String> disabled, List<String> fields, List<Masker.CustomRule> custom) {}

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile Masker.Rules rules;

    public MaskingStore(ScraperProperties props) {
        this.file = Path.of(props.maskingFile()).toAbsolutePath();
        Masker.Rules initial = props.masking();
        if (Files.isReadable(file)) {
            try {
                initial = toRules(mapper.readValue(file.toFile(), Config.class));
                log.info("Pengaturan penyamaran dibaca dari {}", file);
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException("File pengaturan penyamaran rusak (" + file + "): " + e.getMessage(), e);
            }
        }
        this.rules = initial;
    }

    public Masker.Rules rules() { return rules; }

    public Config config() {
        Masker.Rules r = rules;
        return new Config(r.disabled().stream().map(Enum::name).sorted().toList(), r.fields(), r.custom());
    }

    /** Validates a request and turns it into rules. Throws ApiException(400) with a message a person can act on. */
    public static Masker.Rules toRules(Config c) {
        try {
            Set<Masker.Kind> off = EnumSet.noneOf(Masker.Kind.class);
            for (String k : c.disabled() == null ? List.<String>of() : c.disabled()) off.add(Masker.Kind.valueOf(k));
            List<String> fields = c.fields() == null ? List.of() : c.fields().stream().map(String::trim).filter(f -> !f.isEmpty()).distinct().toList();
            List<Masker.CustomRule> custom = c.custom() == null ? List.of() : c.custom();
            if (fields.size() > MAX_FIELDS) throw new ApiException(400, "Terlalu banyak nama field (maksimal " + MAX_FIELDS + ").");
            if (custom.size() > MAX_CUSTOM) throw new ApiException(400, "Terlalu banyak aturan regex (maksimal " + MAX_CUSTOM + ").");
            for (Masker.CustomRule r : custom)
                if (r.regex().length() > MAX_REGEX) throw new ApiException(400, Lang.t("Regex \"" + r.name() + "\" terlalu panjang (maksimal " + MAX_REGEX + " karakter).", "Regex \"" + r.name() + "\" is too long (at most " + MAX_REGEX + " characters)."));
            return new Masker.Rules(off, fields, custom);
        } catch (ApiException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, Lang.t("Pengaturan penyamaran tidak valid: ", "Invalid masking settings: ") + e.getMessage());
        }
    }

    /** True when {@code next} masks less than the rules in force: a filter switched off, a field or rule removed. */
    public boolean weakens(Masker.Rules next) {
        Masker.Rules now = rules;
        Set<Masker.Kind> newlyOff = EnumSet.noneOf(Masker.Kind.class);
        newlyOff.addAll(next.disabled());
        newlyOff.removeAll(now.disabled());
        return !newlyOff.isEmpty() || !next.fields().containsAll(now.fields()) || !next.custom().containsAll(now.custom());
    }

    public synchronized void save(Masker.Rules next) {
        persist(next);
        this.rules = next;
    }

    private void persist(Masker.Rules r) {
        try {
            Path dir = file.getParent();
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "masking", ".tmp");
            try {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) { /* not POSIX */ }
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(),
                    new Config(r.disabled().stream().map(Enum::name).sorted().toList(), new ArrayList<>(r.fields()), r.custom()));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new ApiException(500, Lang.t("Pengaturan penyamaran tidak bisa disimpan: ", "Masking settings could not be saved: ") + e.getMessage());
        }
    }
}
