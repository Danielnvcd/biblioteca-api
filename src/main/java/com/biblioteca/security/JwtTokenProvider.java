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
                .claim("username", username)
                .claim("role", role)
                .claim("scope", "access")
                .issuedAt(now)
                .expiration(expiry)
                .signWith(secretKey)
                .compact();
    }

    /**
     * Short-lived token (5 min) emitted after a successful password check when
     * the user has 2FA enabled. Required by /verify-2fa to prove the caller
     * actually completed step 1. Carries the "remember" choice so it can't be
     * tampered with between login and verify-2fa.
     */
    public String generate2faStepToken(Integer userId, String username, boolean remember) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + 5 * 60 * 1000L);
        return Jwts.builder()
                .subject(userId.toString())
                .claim("username", username)
                .claim("scope", "2fa-pending")
                .claim("remember", remember)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(secretKey)
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

    /** Issued-at as epoch seconds. */
    public long getIssuedAtEpoch(String token) {
        Date iat = parseToken(token).getIssuedAt();
        return iat == null ? 0L : iat.getTime() / 1000L;
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

    private Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
