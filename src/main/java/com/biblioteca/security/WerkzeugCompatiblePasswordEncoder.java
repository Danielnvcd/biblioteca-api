package com.biblioteca.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.HexFormat;

/**
 * PasswordEncoder compatible con hashes de Flask/Werkzeug (pbkdf2:sha256:...)
 * y también con BCrypt para contraseñas nuevas creadas en Spring Boot.
 */
public class WerkzeugCompatiblePasswordEncoder implements PasswordEncoder {

    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    @Override
    public String encode(CharSequence rawPassword) {
        // Las nuevas contraseñas se guardan en BCrypt
        return bcrypt.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (encodedPassword == null || encodedPassword.isEmpty()) {
            return false;
        }

        // Si el hash empieza con pbkdf2: es de Flask/Werkzeug
        if (encodedPassword.startsWith("pbkdf2:")) {
            return matchesWerkzeug(rawPassword.toString(), encodedPassword);
        }

        // Si empieza con $2a$, $2b$ etc. es BCrypt
        if (encodedPassword.startsWith("$2")) {
            return bcrypt.matches(rawPassword, encodedPassword);
        }

        return false;
    }

    /**
     * Verifica un hash de Werkzeug con formato:
     * pbkdf2:sha256:<iterations>$<salt>$<hash>
     */
    private boolean matchesWerkzeug(String rawPassword, String encoded) {
        try {
            // Formato: pbkdf2:sha256:600000$salt$hash
            String withoutPrefix = encoded.substring("pbkdf2:".length());
            String[] parts = withoutPrefix.split(":", 2);
            if (parts.length < 2) return false;

            String algorithm = parts[0]; // sha256
            String rest = parts[1];      // iterations$salt$hash  (o salt$hash sin iteraciones)

            String[] segments = rest.split("\\$");
            
            int iterations;
            String salt;
            String expectedHex;

            if (segments.length == 3) {
                // Formato moderno: pbkdf2:sha256:600000$salt$hash
                iterations = Integer.parseInt(segments[0]);
                salt = segments[1];
                expectedHex = segments[2];
            } else if (segments.length == 2) {
                // Formato legacy: pbkdf2:sha256$salt$hash (sin iteraciones explícitas)
                iterations = 150000;
                salt = segments[0];
                expectedHex = segments[1];
            } else {
                return false;
            }

            String jcaAlgorithm = "PBKDF2WithHmac" + algorithm.toUpperCase().replace("-", "");
            SecretKeyFactory skf = SecretKeyFactory.getInstance(jcaAlgorithm);
            PBEKeySpec spec = new PBEKeySpec(
                rawPassword.toCharArray(),
                salt.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                iterations,
                expectedHex.length() * 4  // bits = hex_chars * 4
            );
            byte[] computedHash = skf.generateSecret(spec).getEncoded();
            String computedHex = HexFormat.of().formatHex(computedHash);

            return constantTimeEquals(computedHex, expectedHex);

        } catch (NoSuchAlgorithmException | InvalidKeySpecException | NumberFormatException e) {
            return false;
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
