package com.biblioteca.security;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EncryptionServiceTest {

    /** Clave de prueba: 32 bytes en base64 (256 bits AES). NO usar en prod. */
    private static final String TEST_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private final EncryptionService service = new EncryptionService(TEST_KEY);

    @Test
    void encryptDecrypt_roundTrip() {
        String plain = "JBSWY3DPEHPK3PXP";  // ejemplo de TOTP secret base32

        String cipher = service.encrypt(plain);
        String back = service.decrypt(cipher);

        assertThat(back).isEqualTo(plain);
    }

    @Test
    void encrypt_outputAlwaysHasGcmPrefix() {
        String cipher = service.encrypt("hola");
        assertThat(cipher).startsWith("gcm:");
    }

    @Test
    void encrypt_outputExceeds32Chars_whichWasTheBugInV1Schema() {
        // El bug #1 del review: la columna VARCHAR(32) cortaba el ciphertext.
        // Confirmamos aquí que cualquier salida real supera holgadamente 32 chars.
        String cipher = service.encrypt("x");
        assertThat(cipher.length()).isGreaterThan(32);
    }

    @Test
    void encrypt_producesDifferentCiphertextEachTime() {
        // IV aleatorio → mismo plaintext jamás produce el mismo ciphertext.
        String a = service.encrypt("secret");
        String b = service.encrypt("secret");

        assertThat(a).isNotEqualTo(b);
        assertThat(service.decrypt(a)).isEqualTo("secret");
        assertThat(service.decrypt(b)).isEqualTo("secret");
    }

    @Test
    void decrypt_passesThroughLegacyPlaintext() {
        // Si la BD aún tiene valores antiguos sin el prefijo gcm:, el servicio
        // los devuelve sin tocar. Permite rollout sin migración forzada.
        String legacy = "JBSWY3DPEHPK3PXP";
        assertThat(service.decrypt(legacy)).isEqualTo(legacy);
    }

    @Test
    void decrypt_malformedCiphertextThrows() {
        assertThatThrownBy(() -> service.decrypt("gcm:not-valid"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void decrypt_nullReturnsNull() {
        assertThat(service.decrypt(null)).isNull();
    }

    @Test
    void encrypt_nullReturnsNull() {
        assertThat(service.encrypt(null)).isNull();
    }

    @Test
    void constructor_rejectsKeyOfWrongLength() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);  // 128 bits
        assertThatThrownBy(() -> new EncryptionService(shortKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("256-bit");
    }
}
