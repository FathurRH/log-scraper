package demo.logscraper;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.SshException;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.EnumSet;

/** Runs one read-only command on one server over SSH. Nothing is installed on the server: it uses the grep already there. */
@Component
public class SshSearcher {
    public enum Status {
        OK, KOSONG, TIDAK_TERJANGKAU, LOGIN_GAGAL, TIMEOUT, DITOLAK, KUNCI_BERUBAH, BELUM_DIATUR, FORMAT_TIDAK_DIKENALI, ERROR
    }

    /** {@code fingerprint} is the host key the server presented (SHA256:...), whenever the connection got that far. */
    public record Outcome(String server, Status status, String stdout, String note, long millis, String fingerprint) {
        public Outcome(String server, Status status, String stdout, String note, long millis) {
            this(server, status, stdout, note, millis, null);
        }
    }

    /** What we expect from, and what we saw of, one server's host key during one connection. */
    static final class HostKeys {
        final String expected;
        volatile String seen;
        volatile boolean mismatch;

        HostKeys(String expected) { this.expected = expected == null || expected.isBlank() ? null : expected; }
    }

    private static final class KeyProblem extends RuntimeException {
        KeyProblem(String message) { super(message); }
    }

    private final Path keyDir;

    public SshSearcher(ScraperProperties props) {
        this.keyDir = Path.of(props.keyDir()).toAbsolutePath().normalize();
    }

    /**
     * One SSH client per connection, so the host key check below belongs to exactly this server: a pinned fingerprint
     * can never be applied to, or skipped for, another connection running at the same time.
     * A server is refused only when it has a pinned fingerprint and presents another key. Without a pin the key is
     * accepted (and shown by "test connection") so that a first setup does not need a known_hosts file.
     */
    private static SshClient newClient(HostKeys keys) {
        SshClient c = SshClient.setUpDefaultClient();
        c.setServerKeyVerifier((session, remote, key) -> {
            keys.seen = KeyUtils.getFingerPrint(key);
            if (keys.expected != null && !keys.expected.equals(keys.seen)) {
                keys.mismatch = true;
                return false;
            }
            return true;
        });
        c.start();
        return c;
    }

    public Outcome run(ScraperProperties.Server s, String command, Duration timeout) {
        long t0 = System.currentTimeMillis();
        HostKeys keys = new HostKeys(s.hostKeyFingerprint());
        SshClient client = newClient(keys);
        try (ClientSession session = client.connect(s.username(), s.host(), s.port()).verify(timeout).getSession()) {
            authenticate(session, s);
            session.auth().verify(timeout);
            ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
            try (ChannelExec ch = session.createExecChannel(command)) {
                ch.setOut(out);
                ch.setErr(err);
                ch.open().verify(timeout);
                var events = ch.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), timeout);
                long ms = System.currentTimeMillis() - t0;
                if (!events.contains(ClientChannelEvent.CLOSED))
                    return new Outcome(s.name(), Status.TIMEOUT, "", "Server tidak selesai menjawab dalam " + timeout.toSeconds() + " detik", ms, keys.seen);
                Integer exit = ch.getExitStatus();
                String stdout = out.toString(StandardCharsets.UTF_8), stderr = err.toString(StandardCharsets.UTF_8).trim();
                if (exit != null && exit == 126)
                    return new Outcome(s.name(), Status.DITOLAK, "", "Akun read-only menolak perintah", ms, keys.seen);
                if (!stdout.isBlank()) return new Outcome(s.name(), Status.OK, stdout, stderr.isEmpty() ? null : stderr, ms, keys.seen);
                if (exit == null)
                    return new Outcome(s.name(), Status.ERROR, "", "Server menutup koneksi tanpa hasil", ms, keys.seen);
                if (exit > 1)
                    return new Outcome(s.name(), Status.ERROR, "", stderr.isEmpty() ? "grep gagal (kode " + exit + ")" : stderr, ms, keys.seen);
                return new Outcome(s.name(), Status.KOSONG, "", stderr.isEmpty() ? null : stderr, ms, keys.seen);
            }
        } catch (Exception e) {
            long ms = System.currentTimeMillis() - t0;
            if (keys.mismatch)
                return new Outcome(s.name(), Status.KUNCI_BERUBAH, "",
                        "Sidik jari server berbeda dari yang disimpan (" + keys.seen + "). Pastikan server memang diganti sebelum menyimpan yang baru", ms, keys.seen);
            if (e instanceof KeyProblem) return new Outcome(s.name(), Status.LOGIN_GAGAL, "", e.getMessage(), ms, keys.seen);
            return new Outcome(s.name(), classify(e), "", friendly(e), ms, keys.seen);
        } finally {
            client.stop();
        }
    }

    private void authenticate(ClientSession session, ScraperProperties.Server s) throws Exception {
        boolean any = false;
        if (s.keyFile() != null && !s.keyFile().isBlank()) {
            Path p = keyDir.resolve(s.keyFile()).normalize();
            if (!p.startsWith(keyDir) || !Files.isReadable(p))
                throw new KeyProblem("File kunci \"" + s.keyFile() + "\" tidak ditemukan di folder kunci");
            try {
                for (KeyPair kp : new FileKeyPairProvider(p).loadKeys(session)) {
                    session.addPublicKeyIdentity(kp);
                    any = true;
                }
            } catch (Exception e) {
                throw new KeyProblem("File kunci \"" + s.keyFile() + "\" tidak bisa dibaca (kunci dengan passphrase belum didukung)");
            }
        }
        if (s.password() != null && !s.password().isBlank()) {
            session.addPasswordIdentity(s.password());
            any = true;
        }
        if (!any) throw new KeyProblem("Belum ada password atau kunci SSH untuk server ini");
    }

    private static Status classify(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof ConnectException || c instanceof UnknownHostException
                    || c instanceof java.nio.channels.UnresolvedAddressException) return Status.TIDAK_TERJANGKAU;
            String m = String.valueOf(c.getMessage()).toLowerCase();
            if (m.contains("auth")) return Status.LOGIN_GAGAL;
            if (m.contains("timeout") || m.contains("timed out")) return Status.TIMEOUT;
        }
        return e instanceof SshException ? Status.TIDAK_TERJANGKAU : Status.ERROR;
    }

    private static String friendly(Throwable e) {
        return switch (classify(e)) {
            case TIDAK_TERJANGKAU -> "Server tidak bisa dihubungi (mati atau jaringan terputus)";
            case LOGIN_GAGAL -> "Login SSH ditolak, cek username, password atau kunci SSH di pengaturan server";
            case TIMEOUT -> "Koneksi ke server terlalu lama";
            default -> "Gagal: " + e.getClass().getSimpleName();
        };
    }

}
