package demo.logscraper;

import java.time.LocalDateTime;
import java.util.List;

/** One log event: a timestamped line plus any lines that belong to it, such as a Java stack trace. */
public record LogEntry(String server, LocalDateTime time, String level, String thread, String logger,
                       String message, List<String> extra) {
    public boolean isError() { return "ERROR".equals(level); }
    public boolean isWarn() { return "WARN".equals(level); }
    public String fullText() { return extra.isEmpty() ? message : message + "\n" + String.join("\n", extra); }
}
