package demo.logscraper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.Callable;

/**
 * The language of the current request: Bahasa Indonesia (default) or English.
 * The pages send X-Lang (or ?lang=en); every message the server writes for people goes through {@link #t}.
 */
public final class Lang {
    private static final ThreadLocal<Boolean> EN = ThreadLocal.withInitial(() -> false);

    public static boolean en() { return EN.get(); }

    public static void set(boolean en) { EN.set(en); }

    public static void clear() { EN.remove(); }

    /** The Bahasa text, or the English one when the request asked for English. */
    public static String t(String id, String en) { return en() ? en : id; }

    /** Runs a task on another thread in the caller's language. */
    public static <T> Callable<T> wrap(Callable<T> task) {
        boolean en = en();
        return () -> {
            set(en);
            try { return task.call(); } finally { clear(); }
        };
    }

    static boolean wantsEnglish(String header, String param) {
        String v = param != null && !param.isBlank() ? param : header;
        return v != null && v.trim().toLowerCase(java.util.Locale.ROOT).startsWith("en");
    }

    @Component
    public static class Filter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            set(wantsEnglish(req.getHeader("X-Lang"), req.getParameter("lang")));
            try { chain.doFilter(req, res); } finally { clear(); }
        }
    }

    private Lang() {}
}
