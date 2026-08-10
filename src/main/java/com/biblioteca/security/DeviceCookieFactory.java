package com.biblioteca.security;

import org.springframework.core.env.Environment;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;

/**
 * Cookie que identifica al NAVEGADOR (no a la sesión) para poder distinguir
 * "iniciaste sesión desde tu compu de siempre" de "alguien entró desde un
 * lugar nuevo".
 *
 * Es un valor aleatorio y opaco: no lleva usuario, ni firma, ni nada
 * deducible. No autentica ni autoriza nada — lo peor que puede hacer quien la
 * robe o la falsifique es evitar que salte un aviso, o provocar uno de más.
 * Por eso no necesita rotación ni revocación.
 *
 * Mismo criterio de SameSite/Secure que {@link RefreshCookieFactory}: en prod
 * el frontend vive en otro dominio, así que hace falta None+Secure; en dev,
 * Lax (Chrome rechaza None sin Secure sobre http://localhost).
 *
 * Path=/api/auth — solo la necesitan los endpoints de inicio de sesión.
 */
@Component
public class DeviceCookieFactory {

    public static final String COOKIE_NAME = "deviceId";
    private static final String PATH = "/api/auth";

    /**
     * 400 días: es el techo que Chrome impone a la vida de una cookie desde
     * 2022. Pedir más no la hace durar más, solo la recorta en silencio.
     */
    private static final Duration MAX_AGE = Duration.ofDays(400);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final boolean secure;
    private final String sameSite;

    public DeviceCookieFactory(Environment env) {
        boolean prod = Arrays.asList(env.getActiveProfiles()).contains("prod");
        this.secure = prod;
        this.sameSite = prod ? "None" : "Lax";
    }

    /** 256 bits en Base64 URL-safe sin padding — cabe en una cookie sin escapes. */
    public String newValue() {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    public ResponseCookie build(String value) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path(PATH)
                .maxAge(MAX_AGE)
                .build();
    }
}
