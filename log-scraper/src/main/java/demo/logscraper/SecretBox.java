package demo.logscraper;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Keeps server passwords unreadable on disk: AES-256-GCM, a fresh random nonce for every value, and a key that is
 * either given in the SETTINGS_KEY environment variable (base64, 32 bytes) or generated once into an owner-only file
 * in the key folder. A stored value looks like {@code enc:v1:...}; anything else is treated as plain text so that
 * passwords typed into application.yml still work and get encrypted the first time settings are saved.
 */
public final class SecretBox {
    private static final String PREFIX = "enc:v1:";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;

    public SecretBox(byte[] key32) {
        if (key32.length != 32) throw new IllegalArgumentException("Kunci enkripsi harus 32 byte");
        this.key = new SecretKeySpec(key32, "AES");
    }

    /** The key from SETTINGS_KEY if set, otherwise from (or newly created in) {@code keyDir/.settings-key}. */
    public static SecretBox open(Path keyDir, String envValue) {
        try {
            if (envValue != null && !envValue.isBlank()) return new SecretBox(Base64.getDecoder().decode(envValue.strip()));
            Path f = keyDir.resolve(".settings-key");
            if (Files.exists(f)) return new SecretBox(Base64.getDecoder().decode(Files.readString(f).strip()));
            Files.createDirectories(keyDir);
            byte[] k = new byte[32];
            RANDOM.nextBytes(k);
            Files.writeString(f, Base64.getEncoder().encodeToString(k) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            try {
                Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // not a POSIX file system
            }
            return new SecretBox(k);
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Kunci enkripsi pengaturan tidak bisa dibaca atau dibuat: " + e.getMessage(), e);
        }
    }

    public String seal(String plain) {
        if (plain == null || plain.startsWith(PREFIX)) return plain;
        try {
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + ct.length).put(nonce).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String open(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) return stored;
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Password tersimpan tidak bisa dibuka: kunci enkripsi berbeda dari saat disimpan?", e);
        }
    }
}
