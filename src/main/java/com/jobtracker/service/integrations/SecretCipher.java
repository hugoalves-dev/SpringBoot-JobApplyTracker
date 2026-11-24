package com.jobtracker.service.integrations;

import com.jobtracker.config.IntegrationProperties;
import com.jobtracker.exception.ServiceUnavailableException;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for user-supplied integration secrets (API keys, OAuth tokens).
 * Ciphertext format: {@code v1:base64(iv || ciphertext+tag)}. The version prefix leaves room for
 * key rotation without a schema change.
 */
@Component
public class SecretCipher {

    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(IntegrationProperties properties) {
        this.key = parseKey(properties.getEncryptionKey());
    }

    public boolean isConfigured() {
        return key != null;
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        requireKey();
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted);
            return PREFIX + Base64.getEncoder().encodeToString(buffer.array());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to encrypt integration secret", ex);
        }
    }

    public String decrypt(String ciphertext) {
        if (ciphertext == null) {
            return null;
        }
        requireKey();
        if (!ciphertext.startsWith(PREFIX)) {
            throw new IllegalStateException("Unsupported integration secret format");
        }
        try {
            byte[] payload = Base64.getDecoder().decode(ciphertext.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, payload, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(payload, IV_BYTES, payload.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("Failed to decrypt integration secret; was the encryption key rotated?", ex);
        }
    }

    /** Shows only the last four characters, e.g. {@code ••••1a2b}. */
    public static String mask(String secret) {
        if (secret == null || secret.isBlank()) {
            return null;
        }
        String tail = secret.length() <= 8 ? "" : secret.substring(secret.length() - 4);
        return "••••" + tail;
    }

    private void requireKey() {
        if (key == null) {
            throw new ServiceUnavailableException(
                    "Integration secrets cannot be stored: APP_INTEGRATIONS_ENCRYPTION_KEY is not configured on the server");
        }
    }

    private static SecretKeySpec parseKey(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("app.integrations.encryption-key must be Base64", ex);
        }
        if (bytes.length != 32) {
            throw new IllegalStateException("app.integrations.encryption-key must decode to 32 bytes (AES-256)");
        }
        return new SecretKeySpec(bytes, "AES");
    }
}
