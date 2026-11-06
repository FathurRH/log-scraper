package demo.logscraper;

import java.util.List;

/** A server as shown to the browser or a script: everything except the password, which is never sent back. */
public record ServerView(String name, String role, String host, int port, String username, boolean hasPassword,
                         String keyFile, String hostKeyFingerprint, List<String> logPaths) {
    public static ServerView of(ScraperProperties.Server s) {
        return new ServerView(s.name(), s.role(), s.host(), s.port(), s.username(),
                s.password() != null && !s.password().isBlank(), s.keyFile(), s.hostKeyFingerprint(), s.logPaths());
    }
}
