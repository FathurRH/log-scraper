package demo.sampleapp;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Writes Spring-Boot-style log lines: 2026-11-02 21:15:03.123  INFO [http-exec-3] c.d.p.RequestService : message */
final class AppLog {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static PrintWriter out;

    static synchronized void init(Path dir, String file) throws IOException {
        Files.createDirectories(dir);
        out = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(dir.resolve(file).toFile(), true), StandardCharsets.UTF_8), true);
    }

    static void info(String logger, String msg) { write("INFO", logger, msg, null); }
    static void warn(String logger, String msg) { write("WARN", logger, msg, null); }
    static void error(String logger, String msg, Throwable t) { write("ERROR", logger, msg, t); }
    static void debug(String logger, String msg) { write("DEBUG", logger, msg, null); }

    private static synchronized void write(String level, String logger, String msg, Throwable t) {
        String line = String.format("%s %5s [%s] %s : %s", LocalDateTime.now().format(TS), level,
                Thread.currentThread().getName(), logger, msg);
        out.println(line);
        System.out.println(line);
        if (t != null) {
            t.printStackTrace(out);
            out.flush();
        }
    }

    private AppLog() {}
}
