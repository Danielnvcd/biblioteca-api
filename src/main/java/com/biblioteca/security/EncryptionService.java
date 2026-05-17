package com.biblioteca.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Symmetric encryption for at-rest sensitive fields (currently: TOTP secrets).
 * Uses AES-256-GCM with a random IV per ciphertext.
 *
 * Output format: "gcm:<base64(iv)>:<base64(ciphertext+tag)>"
 * The prefix lets readers detect ciphertexts produced by this service and
 * distinguish them from legacy plaintext values, so we can roll out the
 * change without a forced re-encryption migration.
 *
 * Key source: {@code app.encryption.key} (env var APP_ENCRYPTION_KEY), a
 * Base64-encoded 256-bit key. In dev a fallback is provided; in prod the
 * value must come from env or the bean will fail at startup.
 */
@Service
public class EncryptionService {

    public static final String PREFIX = "gcm:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;

    public EncryptionService(@Value("${app.encryption.key}") String base64Key) {
        byte[] raw = Base64.getDecoder().decode(base64Key);
        if (raw.length != 32) {
            throw new IllegalStateException(
                    "app.encryption.key must be a Base64-encoded 256-bit key (32 bytes)");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    /** Encrypts a UTF-8 string. Output always begins with {@link #PREFIX}. */
    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return PREFIX
                    + Base64.getEncoder().encodeToString(iv)
                    + ":" + Base64.getEncoder().encodeToString(ct);
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    /**
     * Decrypts a ciphertext produced by {@link #encrypt}. If the value does
     * not have the GCM prefix (legacy plaintext from before this service
     * existed), it is returned as-is — the caller treats it as already-clear.
     */
    public String decrypt(String value) {
        if (value == null) return null;
        if (!value.startsWith(PREFIX)) return value; // legacy plaintext
        try {
            String[] parts = value.substring(PREFIX.length()).split(":", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Malformed ciphertext");
            }
            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] ct = Base64.getDecoder().decode(parts[1]);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }
}
