package demo.logscraper;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SearchController {
    private final SearchService service;

    public SearchController(SearchService service) {
        this.service = service;
    }

    @GetMapping("/search")
    public SearchService.Answer search(@RequestParam(required = false) String q,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
                                       @RequestParam(required = false) Integer hours,
                                       HttpServletRequest req) {
        if (hours != null && from == null) {
            if (hours < 1 || hours > 24 * 31) throw new SearchService.BadQuery("Rentang waktu harus antara 1 jam dan 31 hari.");
            from = (to == null ? LocalDateTime.now() : to).minusHours(hours);
        }
        return service.search(q, from, to, req.getRemoteAddr());
    }

    /** Which masking filters are on, so the page can show them. Read-only: masking is changed in the settings (admin token), never from this page. */
    @GetMapping("/masking")
    public Map<String, Object> masking() {
        Masker.Rules r = service.maskingRules();
        List<String> active = java.util.Arrays.stream(Masker.Kind.values()).filter(k -> !r.disabled().contains(k)).map(Enum::name).toList();
        return Map.of("active", active, "disabled", r.disabled().stream().map(Enum::name).sorted().toList(),
                "extraFields", r.fields(), "customRules", r.custom().stream().map(Masker.CustomRule::name).toList());
    }

    @GetMapping("/samples")
    public List<String> samples() {
        return service.sampleIds();
    }

    @ExceptionHandler(SearchService.BadQuery.class)
    public ResponseEntity<Map<String, String>> bad(SearchService.BadQuery e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> badTime(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "Format waktu tidak dikenali. Contoh: 2026-11-02T21:00"));
    }
}
