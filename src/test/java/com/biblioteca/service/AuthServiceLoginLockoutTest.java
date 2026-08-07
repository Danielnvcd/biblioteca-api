package com.biblioteca.service;

import com.biblioteca.dto.LoginRequest;
import com.biblioteca.exception.ApiException;
import com.biblioteca.model.User;
import com.biblioteca.repository.UserRepository;
import com.biblioteca.security.EncryptionService;
import com.biblioteca.security.JwtTokenProvider;
import com.biblioteca.security.RefreshTokenService;
import com.biblioteca.security.TotpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cubre el bloqueo por cuenta y la resistencia a enumeración de usuarios en
 * /login. Antes de estos tests el login no consultaba failed_password_attempts
 * ni password_locked_until (las columnas existían desde V6 pero solo las usaba
 * /change-password), y devolvía el 401 de "usuario inexistente" sin hashear,
 * lo que dejaba un oráculo de timing de ~200 ms.
 */
class AuthServiceLoginLockoutTest {

    private static final String REAL_HASH = "$2a$12$realhashrealhashrealhashre";

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private JwtTokenProvider tokenProvider;
    private RefreshTokenService refreshTokenService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        tokenProvider = mock(JwtTokenProvider.class);
        refreshTokenService = mock(RefreshTokenService.class);
        TotpService totpService = mock(TotpService.class);
        EncryptionService encryptionService = mock(EncryptionService.class);

        when(passwordEncoder.encode(any())).thenReturn("$2a$12$dummydummydummydummydum");
        authService = new AuthService(userRepository, passwordEncoder, tokenProvider,
                totpService, refreshTokenService, encryptionService);
    }

    private static User user(String name) {
        User u = new User();
        u.setId(7);
        u.setUsername(name);
        u.setPasswordHash(REAL_HASH);
        u.setRole("user");
        return u;
    }

    private static LoginRequest request(String username, String password) {
        LoginRequest r = new LoginRequest();
        r.setUsername(username);
        r.setPassword(password);
        return r;
    }

    @Test
    void unknownUserStillBurnsAHashSoTimingDoesNotLeakExistence() {
        when(userRepository.findByUsername("fantasma")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(request("fantasma", "x"), "1.1.1.1", "ua"))
                .isInstanceOf(ApiException.class)
                .hasMessage("Credenciales incorrectas");

        // El punto del arreglo: se gasta el mismo trabajo de BCrypt que en el
        // camino "usuario existe, contraseña mala".
        verify(passwordEncoder).matches(eq("x"), eq("$2a$12$dummydummydummydummydum"));
    }

    @Test
    void wrongPasswordIncrementsTheCounterWithoutLocking() {
        User u = user("ana");
        when(userRepository.findByUsername("ana")).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("mala", REAL_HASH)).thenReturn(false);

        assertThatThrownBy(() -> authService.login(request("ana", "mala"), "1.1.1.1", "ua"))
                .isInstanceOf(ApiException.class);

        assertThat(u.getFailedPasswordAttempts()).isEqualTo(1);
        assertThat(u.getPasswordLockedUntil()).isNull();
        verify(userRepository).save(u);
    }

    @Test
    void tenthConsecutiveFailureLocksTheAccount() {
        User u = user("ana");
        u.setFailedPasswordAttempts(9);
        when(userRepository.findByUsername("ana")).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("mala", REAL_HASH)).thenReturn(false);

        assertThatThrownBy(() -> authService.login(request("ana", "mala"), "1.1.1.1", "ua"))
                .isInstanceOf(ApiException.class);

        assertThat(u.getFailedPasswordAttempts()).isEqualTo(10);
        assertThat(u.getPasswordLockedUntil()).isAfter(LocalDateTime.now().plusMinutes(14));
    }

    @Test
    void lockedAccountIsRejectedEvenWithTheCorrectPassword() {
        User u = user("ana");
        u.setFailedPasswordAttempts(10);
        u.setPasswordLockedUntil(LocalDateTime.now().plusMinutes(10));
        when(userRepository.findByUsername("ana")).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("correcta", REAL_HASH)).thenReturn(true);

        assertThatThrownBy(() -> authService.login(request("ana", "correcta"), "1.1.1.1", "ua"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);

        // Cortocircuito real: ni siquiera se verifica la contraseña mientras dura
        // el bloqueo, así que no se puede usar el login como oráculo durante ese rato.
        verify(passwordEncoder, never()).matches("correcta", REAL_HASH);
    }

    @Test
    void expiredLockLetsTheUserBackIn() {
        User u = user("ana");
        u.setFailedPasswordAttempts(10);
        u.setPasswordLockedUntil(LocalDateTime.now().minusSeconds(1));
        when(userRepository.findByUsername("ana")).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("correcta", REAL_HASH)).thenReturn(true);
        when(tokenProvider.generateToken(7, "ana", "user")).thenReturn("token-ok");

        var result = authService.login(request("ana", "correcta"), "1.1.1.1", "ua");

        assertThat(result.body().getToken()).isEqualTo("token-ok");
        assertThat(u.getFailedPasswordAttempts()).isZero();
        assertThat(u.getPasswordLockedUntil()).isNull();
    }

    @Test
    void successfulLoginClearsPreviousFailures() {
        User u = user("ana");
        u.setFailedPasswordAttempts(3);
        when(userRepository.findByUsername("ana")).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("correcta", REAL_HASH)).thenReturn(true);
        when(tokenProvider.generateToken(7, "ana", "user")).thenReturn("token-ok");

        authService.login(request("ana", "correcta"), "1.1.1.1", "ua");

        assertThat(u.getFailedPasswordAttempts()).isZero();
        verify(userRepository).save(u);
    }

    @Test
    void rememberFalseIssuesNoRefreshToken() {
        User u = user("ana");
        when(userRepository.findByUsername("ana")).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("correcta", REAL_HASH)).thenReturn(true);
        when(tokenProvider.generateToken(7, "ana", "user")).thenReturn("token-ok");

        var result = authService.login(request("ana", "correcta"), "1.1.1.1", "ua");

        assertThat(result.refreshToken()).isNull();
        verify(refreshTokenService, never()).issue(any(), any(), any());
    }
}
