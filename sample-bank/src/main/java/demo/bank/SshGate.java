package demo.bank;

import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.command.CommandFactory;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.shell.ProcessShellFactory;

import java.io.*;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/**
 * Stands in for the server's own sshd. The "logreader" account is read-only: it may only run grep/zgrep
 * (optionally chained with ';' or '|' into head/sort/uniq), and nothing that writes.
 */
final class SshGate {
    private static final Pattern ALLOWED = Pattern.compile(
            "^(LC_ALL=C )?(z?grep|head|sort|uniq)( [^;&|`$<>]*)?( ?[;|] ?(LC_ALL=C )?(z?grep|head|sort|uniq)( [^;&|`$<>]*)?)*$");

    static void start(int port, String user, String password) throws IOException {
        SshServer sshd = SshServer.setUpDefaultServer();
        sshd.setPort(port);
        sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(Paths.get("/tmp/hostkey.ser")));
        sshd.setPasswordAuthenticator((u, p, s) -> user.equals(u) && password.equals(p));
        sshd.setCommandFactory((channel, command) -> {
            if (!ALLOWED.matcher(stripQuoted(command)).matches()) {
                AppLog.warn("sshd", "rejected command from read-only user: " + command);
                return new Rejected(command);
            }
            // run through /bin/sh like a real sshd, so globs such as *.log and pipes work
            return new ProcessShellFactory(command, "/bin/sh", "-c", command).createShell(channel);
        });
        sshd.start();
        AppLog.info("sshd", "read-only SSH ready on port " + port + " for user " + user);
    }

    /** Quoted arguments may contain anything, so the allow-list check ignores what is inside '...'. */
    private static String stripQuoted(String cmd) {
        return cmd.replaceAll("'[^']*'", "Q");
    }

    private static final class Rejected implements Command {
        private final String cmd;
        private OutputStream err;
        private org.apache.sshd.server.ExitCallback exit;
        Rejected(String cmd) { this.cmd = cmd; }
        @Override public void setInputStream(InputStream in) {}
        @Override public void setOutputStream(OutputStream out) {}
        @Override public void setErrorStream(OutputStream err) { this.err = err; }
        @Override public void setExitCallback(org.apache.sshd.server.ExitCallback cb) { this.exit = cb; }
        @Override public void start(ChannelSession channel, org.apache.sshd.server.Environment env) throws IOException {
            err.write(("read-only account: command not allowed: " + cmd + "\n").getBytes());
            err.flush();
            exit.onExit(126);
        }
        @Override public void destroy(ChannelSession channel) {}
    }

    private SshGate() {}
}
