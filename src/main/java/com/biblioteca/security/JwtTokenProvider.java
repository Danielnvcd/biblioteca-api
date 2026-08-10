package com.biblioteca.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

@Component
public class JwtTokenProvider {

    /**
     * Emisor y destinatario de los tokens. Con una sola aplicación firmando y
     * validando, `iss`/`aud` no impiden ningún ataque hoy: quien pueda firmar un
     * token con la clave correcta también puede poner los claims correctos.
     *
     * Se emiten y validan igual porque el día que esta clave se comparta con un
     * segundo servicio (un job, otra API del mismo dominio), un token emitido
     * para aquél dejaría de ser aceptable acá — sin `aud` sí lo sería, y ese es
     * un fallo silencioso y difícil de ver venir.
     */
    public static final String ISSUER   = "biblioteca-api";
    public static final String AUDIENCE = "biblioteca-app";

    private final SecretKey secretKey;
    private final long expirationMs;

    public JwtTokenProvider(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiration-ms}") long expirationMs) {
        this.secretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        this.expirationMs = expirationMs;
    }

    public String generateToken(Integer userId, String username, String role) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(userId.toString())
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                // `jti` identifica a ESTE token. Es lo que permite que /logout
                // invalide la sesión que se cierra sin tocar las demás.
                .id(java.util.UUID.randomUUID().toString())
                .claim("username", username)
                .claim("role", role)
                .claim("scope", "access")
                .issuedAt(now)
                .expiration(expiry)
                .signWith(secretKey, Jwts.SIG.HS512)
                .compact();
    }

    /**
     * Short-lived token emitted after a successful password check when the
     * user has 2FA enabled. Required by /verify-2fa and /verify-email-code to
     * prove the caller actually completed step 1. Carries the "remember"
     * choice so it can't be tampered with between login and verification.
     *
     * Duran 10 y no 5 minutos desde que existe el segundo factor por correo:
     * el código enviado vive 10 minutos, y un step token más corto dejaba al
     * usuario con un código válido en la bandeja y ninguna pantalla donde
     * usarlo. Sigue sirviendo únicamente para verificar el segundo factor —
     * no autoriza ninguna otra operación.
     */
    public String generate2faStepToken(Integer userId, String username, boolean remember) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + 10 * 60 * 1000L);
        return Jwts.builder()
                .subject(userId.toString())
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .claim("username", username)
                .claim("scope", "2fa-pending")
                .claim("remember", remember)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(secretKey, Jwts.SIG.HS512)
                .compact();
    }

    public boolean getRememberFromToken(String token) {
        Object r = parseToken(token).get("remember");
        return r instanceof Boolean b && b;
    }

    public String getScopeFromToken(String token) {
        Object s = parseToken(token).get("scope");
        return s == null ? "access" : s.toString();
    }

    /** Issued-at as epoch milliseconds. */
    public long getIssuedAtEpochMillis(String token) {
        Date iat = parseToken(token).getIssuedAt();
        return iat == null ? 0L : iat.getTime();
    }

    /**
     * Identificador único del token (`jti`), o null si no lo lleva.
     *
     * Devuelve null en los tokens emitidos antes de que existiera el claim: no
     * se pueden revocar individualmente, pero se siguen aceptando hasta que
     * expiran (≤15 min tras el despliegue). Rechazarlos habría cerrado la
     * sesión de todo el mundo al desplegar, sin ganancia de seguridad.
     */
    public String getJtiFromToken(String token) {
        return parseToken(token).getId();
    }

    /** Expiración del token en milisegundos epoch, o 0 si no la lleva. */
    public long getExpirationEpochMillis(String token) {
        Date exp = parseToken(token).getExpiration();
        return exp == null ? 0L : exp.getTime();
    }

    public Integer getUserIdFromToken(String token) {
        Claims claims = parseToken(token);
        return Integer.parseInt(claims.getSubject());
    }

    public String getUsernameFromToken(String token) {
        return parseToken(token).get("username", String.class);
    }

    public String getRoleFromToken(String token) {
        return parseToken(token).get("role", String.class);
    }

    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Verifica firma, issuer, audience y expiración. Un token al que le falte
     * `iss`/`aud`, o que los traiga distintos, es rechazado.
     *
     * Efecto puntual al desplegar: los access tokens emitidos antes de este
     * cambio no traen esos claims y dejan de validar. El cliente recibe 401,
     * su interceptor renueva contra /api/auth/refresh (el refresh token es
     * opaco, no un JWT, así que no se ve afectado) y sigue trabajando. Solo
     * vuelven a login quienes entraron sin "recordarme" y por tanto no tienen
     * cookie de refresh.
     */
    private Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .requireIssuer(ISSUER)
                .requireAudience(AUDIENCE)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
