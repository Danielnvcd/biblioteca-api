package com.biblioteca.security;

import com.biblioteca.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.Optional;

/**
 * Loads the current security-relevant user state for the JWT filter.
 */
@Service
public class SessionInvalidationService {

    private final UserRepository userRepository;

    public SessionInvalidationService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public Optional<UserSecurityState> findSecurityState(Integer userId) {
        return userRepository.findSecurityStateById(userId);
    }

    /** Millisecond precision avoids same-second gaps when passwords are changed. */
    public long passwordChangedEpochMillis(UserSecurityState state) {
        if (state == null || state.passwordChangedAt() == null) {
            return 0L;
        }
        return state.passwordChangedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
