package com.biblioteca.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Refuses to start the prod profile if any of the security-critical secrets
 * is still the development fallback baked into application.yml. Catches the
 * case where someone activates {@code prod} but forgets to set the env var,
 * which would silently leave the app shipping with a publicly-known key.
 *
 * Active only when the {@code prod} profile is selected.
 */
@Component
@Profile("prod")
public class ProdSecretsValidator {

    private static final Logger log = LoggerFactory.getLogger(ProdSecretsValidator.class);

    // These literals are the dev fallbacks pinned in application.yml. If prod
    // ever resolves to these, the operator forgot to set the corresponding
    // env var. Keep these in sync if you ever rotate the dev fallback.
    private static final String DEV_JWT_SECRET =
            "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970"
          + "337336763979244226452948404D635166546A576E5A7234753778214125442A47";
    private static final String DEV_ENCRYPTION_KEY =
            "Zm9yLWRldi1vbmx5LWRvLW5vdC11c2UtaW4tcHJvZCE=";

    private final String jwtSecret;
    private final String encryptionKey;

    public ProdSecretsValidator(
            @Value("${app.jwt.secret}") String jwtSecret,
            @Value("${app.encryption.key}") String encryptionKey) {
        this.jwtSecret = jwtSecret;
        this.encryptionKey = encryptionKey;
    }

    @PostConstruct
    void validate() {
        // equals (no equalsIgnoreCase) — el dev fallback es hex case-sensitive
        // y cualquier variación en el valor real cuenta como secreto distinto.
        if (DEV_JWT_SECRET.equals(jwtSecret)) {
            throw new IllegalStateException(
                "JWT_SECRET is the dev fallback while running prod profile. "
              + "Generate one with `openssl rand -base64 64` and set JWT_SECRET.");
        }
        if (DEV_ENCRYPTION_KEY.equals(encryptionKey)) {
            throw new IllegalStateException(
                "APP_ENCRYPTION_KEY is the dev fallback while running prod profile. "
              + "Generate one with `openssl rand -base64 32` and set APP_ENCRYPTION_KEY.");
        }
        log.info("✅ prod secrets validated — no dev fallbacks detected.");
    }
}
