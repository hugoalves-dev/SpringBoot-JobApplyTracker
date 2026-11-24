package com.jobtracker.unit.integrations;

import com.jobtracker.config.IntegrationProperties;
import com.jobtracker.exception.ServiceUnavailableException;
import com.jobtracker.service.integrations.SecretCipher;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCipherTest {

    static final String KEY = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    static SecretCipher cipher(String key) {
        IntegrationProperties properties = new IntegrationProperties();
        properties.setEncryptionKey(key);
        return new SecretCipher(properties);
    }

    @Test
    void roundTripsAndNeverStoresPlaintext() {
        SecretCipher cipher = cipher(KEY);
        String encrypted = cipher.encrypt("tf-secret-key-123");

        assertThat(encrypted).startsWith("v1:").doesNotContain("tf-secret-key-123");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("tf-secret-key-123");
    }

    @Test
    void usesRandomIvPerEncryption() {
        SecretCipher cipher = cipher(KEY);
        assertThat(cipher.encrypt("same")).isNotEqualTo(cipher.encrypt("same"));
    }

    @Test
    void rejectsTamperedCiphertextAndForeignKeys() {
        SecretCipher cipher = cipher(KEY);
        String encrypted = cipher.encrypt("value");
        int index = 20;
        String tampered = encrypted.substring(0, index) + (encrypted.charAt(index) == 'A' ? 'B' : 'A') + encrypted.substring(index + 1);

        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalStateException.class);

        SecretCipher other = cipher(Base64.getEncoder().encodeToString("ffffffffffffffffffffffffffffffff".getBytes()));
        assertThatThrownBy(() -> other.decrypt(encrypted)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesToStoreSecretsWithoutKey() {
        SecretCipher cipher = cipher(null);
        assertThat(cipher.isConfigured()).isFalse();
        assertThatThrownBy(() -> cipher.encrypt("x")).isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void rejectsKeysOfWrongLength() {
        assertThatThrownBy(() -> cipher(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void masksAllButLastFourCharacters() {
        assertThat(SecretCipher.mask("tf_live_abcdef1234")).isEqualTo("••••1234");
        assertThat(SecretCipher.mask("short")).isEqualTo("••••");
        assertThat(SecretCipher.mask(null)).isNull();
    }
}
