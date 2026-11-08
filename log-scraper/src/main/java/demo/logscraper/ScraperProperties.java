package demo.logscraper;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/** Everything Log Scraper needs to know, from application.yml: which servers, where their logs are, and the limits. */
@ConfigurationProperties("scraper")
public record ScraperProperties(
        List<Server> servers,
        @DefaultValue("5") int timeoutSeconds,
        @DefaultValue("15") int discoverySeconds,
        @DefaultValue("500") int maxResults,
        @DefaultValue("40") int contextLines,
        @DefaultValue("3") int manualMinutesPerServer,
        @DefaultValue({"REQ-88231", "REQ-88232", "REQ-88233"}) List<String> sampleIds,
        @DefaultValue({"COMMITTED", "POSTED"}) List<String> committedMarkers,
        @DefaultValue({"status=FAILED"}) List<String> failedMarkers,
        @DefaultValue("audit.log") String auditFile,
        @DefaultValue("servers.json") String serversFile,
        @DefaultValue("keys") String keyDir,
        @DefaultValue("masking.json") String maskingFile,
        @DefaultValue("") String setupToken,
        @DefaultValue({"/var/log", "/opt", "/app", "/srv", "/u01", "/home"}) List<String> discoveryRoots,
        @DefaultValue Masker.Rules masking) {

    /**
     * One server to search. Log Scraper logs in with a password, a private key from {@code key-dir}, or both.
     * {@code hostKeyFingerprint} pins the server's identity: when set, a server that presents another key is refused.
     */
    public record Server(String name, String role, String host, @DefaultValue("22") int port,
                         String username, String password, String keyFile, String hostKeyFingerprint,
                         List<String> logPaths) {
        public Server {
            if (logPaths == null) logPaths = List.of();
        }

        /** The generated toString would print the password into any log line that mentions a server. */
        @Override
        public String toString() {
            return "Server[" + name + " " + username + "@" + host + ":" + port + ", password=" + (password == null ? "none" : "****") + "]";
        }
    }
}
