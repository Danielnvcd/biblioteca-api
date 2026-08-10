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
        LoginAlertService loginAlertService = mock(LoginAlertService.class);
        // Sin dispositivo conocido y sin aviso: estos tests miran el lockout,
        // no la notificación, pero issueSession() pasa por acá siempre.
        when(loginAlertService.registerDevice(any(), any(), any(), any()))
                .thenReturn(new LoginAlertService.DeviceCheck("device-cookie", false, "Chrome"));

        authService = new AuthService(userRepository, passwordEncoder, tokenProvider,
                totpService, refreshTokenService, encryptionService,
                mock(com.biblioteca.security.AccessTokenDenylistService.class),
                mock(EmailCodeService.class), loginAlertService,
                mock(MailService.class), mock(EmailTemplates.class));
    }

    /** Contexto de request para los tests: los avisos están mockeados. */
    private static final AuthService.LoginContext CTX =
            new AuthService.LoginContext("1.1.1.1", "ua", null);

    private User user(String name) {
        User u = new User();
        u.setId(7);
        u.setUsername(name);
        u.setPasswordHash(REAL_HASH);
        u.setRole("user");
        // Emula el UPDATE condicional que impone el bloqueo: la base solo lo
        // aplica si el contador REAL llegó al umbral. El contador ya no se
        // calcula en Java — se incrementa con un UPDATE y la base decide quién
        // cruzó el umbral, para que N intentos concurrentes no lean todos el
        // mismo valor y esquiven el techo entre todos.
        when(userRepository.lockPasswordIfExhausted(
                eq(7), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenAnswer(inv -> u.getFailedPasswordAttempts() >= (int) inv.getArgument(1) ? 1 : 0);
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

        assertThatThrownBy(() -> authService.login(request("fantasma", "x"), CTX))
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

        assertThatThrownBy(() -> authService.login(request("ana", "mala"), CTX))
                .isInstanceOf(ApiException.class);

        assertThat(u.getFailedPasswordAttempts()).isEqualTo(1);
        assertThat(u.getPasswordLockedUntil()).isNull();
        verify(userRepository).incrementPasswordFailures(7);
    }

    @Test
    void tenthConsecutiveFailureLocksTheAccount() {
        User u = user("ana");
        u.setFailedPasswordAttempts(9);
        when(userRepository.findByUsername("ana")).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("mala", REAL_HASH)).thenReturn(false);

        assertThatThrownBy(() -> authService.login(request("ana", "mala"), CTX))
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

        assertThatThrownBy(() -> authService.login(request("ana", "correcta"), CTX))
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

        var result = authService.login(request("ana", "correcta"), CTX);

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

        authService.login(request("ana", "correcta"), CTX);

        assertThat(u.getFailedPasswordAttempts()).isZero();
        verify(userRepository).clearPasswordFailures(7);
    }

    @Test
    void rememberFalseIssuesNoRefreshToken() {
        User u = user("ana");
        when(userRepository.findByUsername("ana")).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("correcta", REAL_HASH)).thenReturn(true);
        when(tokenProvider.generateToken(7, "ana", "user")).thenReturn("token-ok");

        var result = authService.login(request("ana", "correcta"), CTX);

        assertThat(result.refreshToken()).isNull();
        verify(refreshTokenService, never()).issue(any(), any(), any());
    }
}
