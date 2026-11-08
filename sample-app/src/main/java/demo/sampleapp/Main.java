package demo.sampleapp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * A small sample system made of four services, one per "server":
 * app-01 api-gateway -> app-02 process-service -> app-03 data-service, and app-04 notification-service.
 * The gateway generates traffic on its own and replays three fixed stories that Log Scraper is demoed with:
 * REQ-88231 times out (but the data service still saves the record, twice), REQ-88232 succeeds, REQ-88233 is over quota.
 */
public final class Main {
    static final Set<String> SLOW_IDS = Set.of("REQ-88231");
    static final Set<String> BROKE_IDS = Set.of("REQ-88233");
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1))
            .executor(Executors.newVirtualThreadPerTaskExecutor()).build();
    static final AtomicInteger THREAD = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        String role = env("ROLE", "gateway");
        AppLog.init(Path.of(env("LOG_DIR", "/var/log/app")), role + ".log");
        SshGate.start(Integer.parseInt(env("SSH_PORT", "2222")), env("SSH_USER", "logreader"), env("SSH_PASSWORD", "bacasaja"));

        HttpServer http = HttpServer.create(new InetSocketAddress(Integer.parseInt(env("PORT", "8080"))), 0);
        http.setExecutor(Executors.newFixedThreadPool(16, r -> {
            Thread t = new Thread(r, "http-exec-" + THREAD.incrementAndGet());
            t.setDaemon(true);
            return t;
        }));
        http.createContext("/health", ex -> reply(ex, 200, "UP"));
        switch (role) {
            case "gateway" -> startGateway();
            case "process" -> http.createContext("/process", Main::process);
            case "data" -> http.createContext("/save", Main::save);
            case "notif" -> http.createContext("/send", Main::send);
            default -> throw new IllegalArgumentException("unknown ROLE " + role);
        }
        http.start();
        AppLog.info("Application", "Started " + role + " on port " + env("PORT", "8080"));
        Thread.currentThread().join();
    }

    // ---------------------------------------------------------------- app-01 api-gateway
    static void startGateway() {
        String L = "c.d.gateway.RequestController";
        ScheduledExecutorService ses = Executors.newScheduledThreadPool(4, r -> {
            Thread t = new Thread(r, "http-exec-" + THREAD.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        // the three demo stories, now and every 10 minutes so a "last 1 hour" search always finds them
        ses.scheduleAtFixedRate(() -> {
            submit(L, "REQ-88231", 25);
            sleep(1500);
            submit(L, "REQ-88232", 3);
            sleep(1500);
            submit(L, "REQ-88233", 975);
        }, 8, 600, TimeUnit.SECONDS);
        // background traffic from other users
        ses.scheduleWithFixedDelay(() -> submit(L, "REQ-" + (10000 + ThreadLocalRandom.current().nextInt(89999)),
                1 + ThreadLocalRandom.current().nextInt(500)), 20_000, 900, TimeUnit.MILLISECONDS);
        ses.scheduleAtFixedRate(() -> AppLog.debug("c.d.gateway.HealthIndicator", "downstream health OK process=UP latency=12ms"),
                10, 30, TimeUnit.SECONDS);
    }

    static void submit(String L, String trx, long amount) {
        try {
            String nik = Pii.nik(), phone = Pii.phone(), card = Pii.card();
            long t0 = System.currentTimeMillis();
            AppLog.info(L, "POST /api/v1/requests reqId=" + trx + " customer=\"" + Pii.name() + "\" nik=" + nik
                    + " phone=" + phone + " units=" + amount + " channel=MOBILE");
            AppLog.debug("c.d.gateway.AuthFilter", "reqId=" + trx + " Authorization: Bearer " + Pii.token() + " device=ANDROID");
            HttpResponse<String> res = post(env("PROCESS_URL", "http://app-02:8080") + "/process", Duration.ofSeconds(6),
                    Map.of("reqId", trx, "units", "" + amount, "card", card, "phone", phone, "ref", Pii.ref()));
            long ms = System.currentTimeMillis() - t0;
            if (res.statusCode() == 200)
                AppLog.info(L, "reqId=" + trx + " status=SUCCESS httpStatus=200 elapsed=" + ms + "ms");
            else
                AppLog.warn(L, "reqId=" + trx + " status=FAILED httpStatus=" + res.statusCode() + " reason=" + res.body() + " elapsed=" + ms + "ms");
        } catch (Exception e) {
            AppLog.error(L, "reqId=" + trx + " status=FAILED process-service unreachable", e);
        }
    }

    // ---------------------------------------------------------------- app-02 process-service
    static void process(HttpExchange ex) throws IOException {
        String L = "c.d.process.RequestService";
        Map<String, String> f = form(ex);
        String trx = f.get("reqId");
        AppLog.info(L, "received reqId=" + trx + " units=" + f.get("units") + " card=" + f.get("card") + " ref=" + f.get("ref"));
        AppLog.debug("c.d.process.Validator", "reqId=" + trx + " score=0." + (10 + ThreadLocalRandom.current().nextInt(30)) + " result=PASS");
        Map<String, String> body = Map.of("reqId", trx, "units", f.get("units"), "account", "00" + f.get("card").substring(8, 16));
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                AppLog.info("c.d.process.DataClient", "calling data-service /save reqId=" + trx + " attempt=" + attempt + " timeout=2000ms");
                HttpResponse<String> res = post(env("DATA_URL", "http://app-03:8080") + "/save", Duration.ofSeconds(2), body);
                if (res.statusCode() == 422) {
                    AppLog.warn(L, "reqId=" + trx + " rejected by data-service: " + res.body());
                    reply(ex, 422, res.body());
                    return;
                }
                AppLog.info(L, "reqId=" + trx + " saved ref=" + res.body());
                notifyAsync(trx, f.get("phone"));
                reply(ex, 200, "OK");
                return;
            } catch (java.net.http.HttpTimeoutException e) {
                if (attempt == 1) {
                    AppLog.warn("c.d.process.DataClient", "reqId=" + trx + " data-service timeout after 2000ms, retry 1/1");
                } else {
                    AppLog.error(L, "reqId=" + trx + " FAILED after retry: data-service did not answer in time", wrap(e));
                    reply(ex, 504, "UPSTREAM_TIMEOUT");
                    return;
                }
            } catch (Exception e) {
                AppLog.error(L, "reqId=" + trx + " FAILED calling data-service", e);
                reply(ex, 502, "UPSTREAM_ERROR");
                return;
            }
        }
    }

    private static Exception wrap(Exception e) {
        return new IllegalStateException("UpstreamTimeoutException: no reply from data-service in 2000ms", e);
    }

    static void notifyAsync(String trx, String phone) {
        Thread.ofVirtual().start(() -> {
            try {
                post(env("NOTIF_URL", "http://app-04:8080") + "/send", Duration.ofSeconds(2), Map.of("reqId", trx, "phone", phone));
            } catch (Exception e) {
                AppLog.warn("c.d.process.Notifier", "reqId=" + trx + " notification not sent: " + e.getClass().getSimpleName());
            }
        });
    }

    // ---------------------------------------------------------------- app-03 data-service
    static void save(HttpExchange ex) throws IOException {
        String L = "c.d.data.RecordHandler";
        Map<String, String> f = form(ex);
        String trx = f.get("reqId");
        long t0 = System.currentTimeMillis();
        AppLog.info(L, "save request reqId=" + trx + " account=" + f.get("account") + " units=" + f.get("units"));
        boolean fixed = trx.startsWith("REQ-8823");   // the demo stories always play out the same way
        boolean slow = SLOW_IDS.contains(trx) || (!fixed && ThreadLocalRandom.current().nextInt(100) < 2);
        boolean broke = BROKE_IDS.contains(trx) || (!fixed && ThreadLocalRandom.current().nextInt(100) < 4);
        sleep(slow ? 3400 + ThreadLocalRandom.current().nextInt(300) : 40 + ThreadLocalRandom.current().nextInt(160));
        long ms = System.currentTimeMillis() - t0;
        if (broke && !slow) {
            AppLog.warn(L, "reqId=" + trx + " QUOTA_EXCEEDED remaining quota below requested units, elapsed=" + ms + "ms");
            reply(ex, 422, "QUOTA_EXCEEDED");
            return;
        }
        String ref = "RC" + System.currentTimeMillis() % 100000000;
        if (slow) AppLog.warn("c.d.data.StorageConnector", "reqId=" + trx + " storage slow: replied after " + ms + "ms (limit 2000ms)");
        AppLog.info(L, "reqId=" + trx + " record COMMITTED ref=" + ref + " elapsed=" + ms + "ms");
        try { reply(ex, 200, ref); } catch (IOException gone) {
            AppLog.warn(L, "reqId=" + trx + " caller already disconnected, ref=" + ref + " was saved anyway");
        }
    }

    // ---------------------------------------------------------------- app-04 notification-service
    static void send(HttpExchange ex) throws IOException {
        Map<String, String> f = form(ex);
        String trx = f.get("reqId");
        AppLog.info("c.d.notif.SmsSender", "reqId=" + trx + " SMS to phone=" + f.get("phone") + " text=\"Permintaan Anda selesai diproses. Kode referensi: " + Pii.otp() + "\"");
        AppLog.info("c.d.notif.SmsSender", "reqId=" + trx + " provider accepted msgId=SMS" + ThreadLocalRandom.current().nextInt(1_000_000));
        reply(ex, 200, "SENT");
    }

    // ---------------------------------------------------------------- helpers
    static HttpResponse<String> post(String url, Duration timeout, Map<String, String> form) throws IOException, InterruptedException {
        String body = form.entrySet().stream().map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    static Map<String, String> form(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> m = new HashMap<>();
        for (String kv : body.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(kv.substring(0, i), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    static void reply(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, b.length);
        try (var os = ex.getResponseBody()) { os.write(b); }
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? def : v;
    }

    private Main() {}
}
