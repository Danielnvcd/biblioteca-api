package com.biblioteca.security;

import com.biblioteca.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the most recent password_changed_at for each user, so the JWT filter
 * can reject tokens issued BEFORE that timestamp (i.e. tokens from a session
 * that existed when the password was changed).
 *
 * Implementation note: a 60s in-process cache avoids hitting the DB on every
 * request. On password change the cache entry is invalidated explicitly.
 */
@Service
public class SessionInvalidationService {

    private record Cached(long passwordChangedEpoch, long cachedAtMs) {}

    private static final long TTL_MS = 60_000;
    private static final long NEVER = 0L;

    private final UserRepository userRepository;
    private final Map<Integer, Cached> cache = new ConcurrentHashMap<>();

    public SessionInvalidationService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** Epoch seconds of the user's last password change, or 0 if never. */
    public long passwordChangedEpoch(Integer userId) {
        long now = System.currentTimeMillis();
        Cached c = cache.get(userId);
        if (c != null && now - c.cachedAtMs < TTL_MS) {
            return c.passwordChangedEpoch;
        }
        long epoch = userRepository.findById(userId)
                .map(u -> u.getPasswordChangedAt() != null
                        ? u.getPasswordChangedAt().atZone(ZoneId.systemDefault()).toEpochSecond()
                        : NEVER)
                .orElse(NEVER);
        cache.put(userId, new Cached(epoch, now));
        return epoch;
    }

    /** Call when a user's password changes — invalidates the cached epoch. */
    public void invalidate(Integer userId) {
        cache.remove(userId);
    }
}
