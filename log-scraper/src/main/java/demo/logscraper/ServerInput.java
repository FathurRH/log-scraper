package demo.logscraper;

import java.util.List;

/**
 * What a settings request may change. Every field is optional: a missing field keeps the saved value, so a request can
 * change only the log folders. For {@code password}, {@code keyFile} and {@code hostKeyFingerprint} an empty string clears the value.
 */
public record ServerInput(String role, String host, Integer port, String username, String password, String keyFile,
                          String hostKeyFingerprint, List<String> logPaths) {}
