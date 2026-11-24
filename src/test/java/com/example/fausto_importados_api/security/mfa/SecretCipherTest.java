package com.example.fausto_importados_api.security.mfa;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final byte[] SECRET = "segredo-totp-de-20-b".getBytes(StandardCharsets.UTF_8);

    private final SecretCipher cipher = new SecretCipher(KEY);
    private final UUID user = UUID.randomUUID();

    @Test
    void decryptsWhatItEncrypted() {
        String stored = cipher.encrypt(SECRET, user);

        assertThat(cipher.decrypt(stored, user)).isEqualTo(SECRET);
    }

    @Test
    void storedValueIsVersionedAndDoesNotContainThePlaintext() {
        String stored = cipher.encrypt(SECRET, user);

        assertThat(stored).startsWith("v1:").hasSizeLessThanOrEqualTo(255);
        assertThat(stored).doesNotContain(new String(SECRET, StandardCharsets.UTF_8));
        assertThat(stored).doesNotContain(Base64.getEncoder().encodeToString(SECRET));
    }

    @Test
    void usesAFreshIvEachTime() {
        assertThat(cipher.encrypt(SECRET, user)).isNotEqualTo(cipher.encrypt(SECRET, user));
    }

    @Test
    void aSecretCopiedToAnotherAccountCannotBeDecrypted() {
        String stored = cipher.encrypt(SECRET, user);

        assertThatThrownBy(() -> cipher.decrypt(stored, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tamperedCiphertextIsRejected() {
        String stored = cipher.encrypt(SECRET, user);
        String[] parts = stored.split(":");
        byte[] ciphertext = Base64.getDecoder().decode(parts[2]);
        ciphertext[0] ^= 1;
        String tampered = parts[0] + ":" + parts[1] + ":" + Base64.getEncoder().encodeToString(ciphertext);

        assertThatThrownBy(() -> cipher.decrypt(tampered, user)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aDifferentKeyCannotDecrypt() {
        String stored = cipher.encrypt(SECRET, user);
        byte[] other = new byte[32];
        other[0] = 1;

        SecretCipher otherCipher = new SecretCipher(Base64.getEncoder().encodeToString(other));

        assertThatThrownBy(() -> otherCipher.decrypt(stored, user)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unknownFormatIsRejected() {
        assertThatThrownBy(() -> cipher.decrypt("v9:AAAA:BBBB", user)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt("lixo", user)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesToStartWithoutAValidKey() {
        assertThatThrownBy(() -> new SecretCipher("")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TOTP_ENCRYPTION_KEY");
        assertThatThrownBy(() -> new SecretCipher("não é base64!")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SecretCipher(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
    }
}
