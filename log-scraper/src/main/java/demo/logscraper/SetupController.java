package demo.logscraper;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/**
 * Server-side settings: which servers to search, where their logs are, and the checks to get those right.
 * When {@code scraper.setup-token} is set, every call needs it in the {@code X-Setup-Token} header. Passwords can be
 * written here but are never returned by any call.
 */
@RestController
@RequestMapping("/api/setup")
public class SetupController {
    private final ServerStore store;
    private final SetupService setup;
    private final ScraperProperties props;

    public SetupController(ServerStore store, SetupService setup, ScraperProperties props) {
        this.store = store;
        this.setup = setup;
        this.props = props;
    }

    public record DiscoverRequest(String id, List<String> roots) {}

    public record PathRequest(String path, Integer lines) {}

    @GetMapping("/servers")
    public List<ServerView> servers(@RequestHeader(value = "X-Setup-Token", required = false) String token) {
        auth(token);
        return store.all().stream().map(ServerView::of).toList();
    }

    @GetMapping("/servers/{name}")
    public ServerView one(@PathVariable String name, @RequestHeader(value = "X-Setup-Token", required = false) String token) {
        auth(token);
        return ServerView.of(saved(name));
    }

    /** Adds or changes a server. Fields left out keep their saved value. */
    @PutMapping("/servers/{name}")
    public ServerView put(@PathVariable String name, @RequestBody ServerInput in,
                          @RequestHeader(value = "X-Setup-Token", required = false) String token, HttpServletRequest req) {
        auth(token);
        ScraperProperties.Server merged = ServerStore.merge(store.find(name).orElse(null), name, in);
        store.save(merged);
        setup.audit(req.getRemoteAddr(), "simpan server " + name + " (" + merged.logPaths().size() + " folder log)");
        return ServerView.of(merged);
    }

    @DeleteMapping("/servers/{name}")
    public Map<String, Object> delete(@PathVariable String name, @RequestHeader(value = "X-Setup-Token", required = false) String token,
                                      HttpServletRequest req) {
        auth(token);
        if (!store.delete(name)) throw new ApiException(404, "Server \"" + name + "\" tidak ada.");
        setup.audit(req.getRemoteAddr(), "hapus server " + name);
        return Map.of("deleted", name);
    }

    /** Step 1: can we get in? The body is optional; fields in it are tried on top of the saved server, without saving. */
    @PostMapping("/servers/{name}/test")
    public SetupService.TestResult test(@PathVariable String name, @RequestBody(required = false) ServerInput in,
                                        @RequestHeader(value = "X-Setup-Token", required = false) String token, HttpServletRequest req) {
        auth(token);
        ScraperProperties.Server s = ServerStore.merge(store.find(name).orElse(null), name, in);
        ServerStore.validate(s);
        SetupService.TestResult r = setup.test(s);
        setup.audit(req.getRemoteAddr(), "tes koneksi " + name + " -> " + (r.ok() ? "berhasil" : "gagal"));
        return r;
    }

    /** Makes a key pair for this server. The private key stays here; the answer carries the line the server admin adds. */
    @PostMapping("/servers/{name}/key")
    public SetupService.KeyResult key(@PathVariable String name, @RequestParam(defaultValue = "false") boolean force,
                                      @RequestHeader(value = "X-Setup-Token", required = false) String token, HttpServletRequest req) {
        auth(token);
        ServerStore.validate(ServerStore.merge(null, name, new ServerInput(null, "x", 22, "x", null, null, null, null)));
        SetupService.KeyResult r = setup.generateKey(name, force);
        store.find(name).ifPresent(s -> store.save(ServerStore.merge(s, name,
                new ServerInput(null, null, null, null, null, r.keyFile(), null, null))));
        setup.audit(req.getRemoteAddr(), "buat kunci SSH untuk " + name);
        return r;
    }

    /** Step 2: paste an ID that really happened and find the files that contain it. */
    @PostMapping("/servers/{name}/discover")
    public SetupService.DiscoverResult discover(@PathVariable String name, @RequestBody DiscoverRequest in,
                                                @RequestHeader(value = "X-Setup-Token", required = false) String token, HttpServletRequest req) {
        auth(token);
        SetupService.DiscoverResult r = setup.discover(saved(name), in.id(), in.roots());
        setup.audit(req.getRemoteAddr(), "cari folder log di " + name + " -> " + r.patterns().size() + " pola");
        return r;
    }

    /** Which files does a path or pattern match on the server? */
    @PostMapping("/servers/{name}/check")
    public SetupService.CheckResult check(@PathVariable String name, @RequestBody PathRequest in,
                                          @RequestHeader(value = "X-Setup-Token", required = false) String token) {
        auth(token);
        return setup.check(saved(name), in.path());
    }

    /** Step 3: the first lines of a log, how many the parser understands, and the lines masked. */
    @PostMapping("/servers/{name}/preview")
    public SetupService.PreviewResult preview(@PathVariable String name, @RequestBody PathRequest in,
                                              @RequestHeader(value = "X-Setup-Token", required = false) String token) {
        auth(token);
        return setup.preview(saved(name), in.path(), in.lines() == null ? 20 : in.lines());
    }

    private ScraperProperties.Server saved(String name) {
        return store.find(name).orElseThrow(() -> new ApiException(404, "Server \"" + name + "\" belum disimpan. Simpan dulu dengan PUT /api/setup/servers/" + name));
    }

    private void auth(String token) {
        String want = props.setupToken();
        if (want == null || want.isBlank()) return;
        boolean ok = token != null && MessageDigest.isEqual(want.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
        if (!ok) throw new ApiException(401, "Pengaturan server butuh token admin di header X-Setup-Token.");
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> api(ApiException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(SearchService.BadQuery.class)
    public ResponseEntity<Map<String, String>> bad(SearchService.BadQuery e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
