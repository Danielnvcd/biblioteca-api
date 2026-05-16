package com.biblioteca.security;

import com.biblioteca.exception.ApiException;
import com.biblioteca.model.RefreshToken;
import com.biblioteca.repository.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;

/**
 * Issues, rotates and revokes opaque refresh tokens. Only the SHA-256 hash of
 * each raw token is persisted — the raw value is returned to the caller once
 * and never recoverable from the DB.
 *
 * Reuse detection: if a request presents a refresh token that has already
 * been rotated (revoked_at != null), every active refresh token for that
 * user is revoked. This converts a silent token theft into a visible logout.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private static final int RAW_TOKEN_BYTES = 32; // 256 bits
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository repo;
    private final long refreshExpirationMs;
    /** Used to commit the family-wide revoke even though the rotate() caller
     *  is about to throw and roll back its own transaction. */
    private final TransactionTemplate newTxTemplate;

    public RefreshTokenService(RefreshTokenRepository repo,
                               PlatformTransactionManager txManager,
                               @Value("${app.jwt.refresh-expiration-ms}") long refreshExpirationMs) {
        this.repo = repo;
        this.refreshExpirationMs = refreshExpirationMs;
        this.newTxTemplate = new TransactionTemplate(txManager);
        this.newTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Issues a fresh refresh token for a user. Returns the raw (unhashed) value. */
    @Transactional
    public String issue(Integer userId, String ip, String userAgent) {
        String raw = randomToken();
        RefreshToken rt = new RefreshToken();
        rt.setUserId(userId);
        rt.setTokenHash(hash(raw));
        LocalDateTime now = LocalDateTime.now();
        rt.setCreatedAt(now);
        rt.setExpiresAt(now.plus(java.time.Duration.ofMillis(refreshExpirationMs)));
        rt.setIp(truncate(ip, 45));
        rt.setUserAgent(truncate(userAgent, 200));
        repo.save(rt);
        return raw;
    }

    /**
     * Validates the incoming refresh token, revokes it, and issues a new one
     * for the same user. Returns the new raw token along with the user id so
     * the caller can mint a matching access token.
     */
    @Transactional
    public Rotated rotate(String rawToken, String ip, String userAgent) {
        if (rawToken == null || rawToken.isBlank()) {
            throw ApiException.unauthorized("Sesión expirada");
        }
        Optional<RefreshToken> opt = repo.findByTokenHash(hash(rawToken));
        if (opt.isEmpty()) {
            throw ApiException.unauthorized("Sesión expirada");
        }
        RefreshToken current = opt.get();
        LocalDateTime now = LocalDateTime.now();

        // Reuse detection: presented token was already rotated — possible theft.
        // Commit the family-wide revoke in a NEW transaction so it survives the
        // exception that this method is about to throw (which would otherwise
        // roll back the surrounding @Transactional and undo the revoke).
        if (current.getRevokedAt() != null) {
            Integer userId = current.getUserId();
            log.warn("Refresh token reuse detected for userId={}. Revoking all active tokens.", userId);
            newTxTemplate.executeWithoutResult(status ->
                    repo.revokeAllActiveForUser(userId, LocalDateTime.now()));
            throw ApiException.unauthorized("Sesión inválida, vuelve a iniciar sesión");
        }
        if (current.getExpiresAt().isBefore(now)) {
            throw ApiException.unauthorized("Sesión expirada");
        }

        // Mint successor first so we can point replaced_by at it.
        String newRaw = randomToken();
        RefreshToken next = new RefreshToken();
        next.setUserId(current.getUserId());
        next.setTokenHash(hash(newRaw));
        next.setCreatedAt(now);
        next.setExpiresAt(now.plus(java.time.Duration.ofMillis(refreshExpirationMs)));
        next.setIp(truncate(ip, 45));
        next.setUserAgent(truncate(userAgent, 200));
        repo.save(next);

        current.setRevokedAt(now);
        current.setReplacedBy(next.getId());
        repo.save(current);

        return new Rotated(newRaw, current.getUserId());
    }

    /** Revokes every active refresh token for a user (e.g. after password change). */
    @Transactional
    public void revokeAllForUser(Integer userId) {
        repo.revokeAllActiveForUser(userId, LocalDateTime.now());
    }

    /** Marks the given token as revoked. No-op if it doesn't exist. */
    @Transactional
    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        repo.findByTokenHash(hash(rawToken)).ifPresent(rt -> {
            if (rt.getRevokedAt() == null) {
                rt.setRevokedAt(LocalDateTime.now());
                repo.save(rt);
            }
        });
    }

    public record Rotated(String rawToken, Integer userId) {}

    private static String randomToken() {
        byte[] bytes = new byte[RAW_TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
