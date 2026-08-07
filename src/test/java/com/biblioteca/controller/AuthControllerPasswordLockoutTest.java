package com.biblioteca.controller;

import com.biblioteca.dto.Disable2faRequest;
import com.biblioteca.exception.ApiException;
import com.biblioteca.model.User;
import com.biblioteca.repository.AuditLogRepository;
import com.biblioteca.repository.UserRepository;
import com.biblioteca.security.RefreshCookieFactory;
import com.biblioteca.security.RefreshTokenService;
import com.biblioteca.security.TrustedOriginValidator;
import com.biblioteca.security.UserPrincipal;
import com.biblioteca.service.AuditService;
import com.biblioteca.service.AuthService;
import com.biblioteca.service.FileStorageService;
import com.biblioteca.service.QrCodeService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El contador de intentos fallidos que comparten /change-password y
 * /disable-2fa (columnas de V6) se reseteaba solo en memoria al acertar la
 * contraseña.
 *
 * En /change-password el save() posterior de la contraseña nueva arrastraba el
 * reset sin querer, así que el bug no se veía. En /disable-2fa no: después de
 * validar la contraseña el flujo verifica el código TOTP, y si el código está
 * mal lanza antes de que nadie persista nada. El usuario acertaba la
 * contraseña, se equivocaba de código, y volvía con el contador intacto —
 * arrastrando fallos viejos hacia un bloqueo de 15 minutos que ya no
 * correspondía.
 *
 * La entidad no vive dentro de una transacción con dirty checking, así que sin
 * un save() explícito el cambio nunca llega a la BD.
 */
class AuthControllerPasswordLockoutTest {

    private static final Integer USER_ID = 7;
    private static final String PASSWORD_OK = "correcta";

    private UserRepository userRepository;
    private AuthService authService;
    private PasswordEncoder passwordEncoder;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        authService = mock(AuthService.class);
        passwordEncoder = mock(PasswordEncoder.class);

        controller = new AuthController(
                authService,
                userRepository,
                mock(AuditService.class),
                mock(FileStorageService.class),
                passwordEncoder,
                mock(QrCodeService.class),
                mock(RefreshCookieFactory.class),
                mock(RefreshTokenService.class),
                mock(TrustedOriginValidator.class),
                mock(AuditLogRepository.class));
    }

    private User conFallosPrevios(int intentos) {
        User u = new User();
        u.setId(USER_ID);
        u.setUsername("ana");
        u.setPasswordHash("$2a$12$hash");
        u.setFailedPasswordAttempts(intentos);
        return u;
    }

    private Disable2faRequest body(String code) {
        Disable2faRequest b = new Disable2faRequest();
        b.setCurrentPassword(PASSWORD_OK);
        b.setCode(code);
        return b;
    }

    private static UserPrincipal principal() {
        return new UserPrincipal(USER_ID, "ana", "user");
    }

    @Test
    void resetaYPersisteElContadorAunqueElCodigoTotpFalle() {
        User user = conFallosPrevios(3);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        // Contraseña bien, código mal: el flujo aborta acá.
        doThrow(ApiException.badRequest("Código incorrecto, intenta de nuevo"))
                .when(authService).disable2fa(any(), anyString());

        assertThatThrownBy(() -> controller.disable2fa(
                principal(), body("000000"), mock(HttpServletRequest.class)))
                .isInstanceOf(ApiException.class);

        // Lo que se protege: el reset se guardó ANTES de que el código fallara.
        verify(userRepository).save(user);
        assertThat(user.getFailedPasswordAttempts()).isZero();
        assertThat(user.getPasswordLockedUntil()).isNull();
    }

    @Test
    void noEscribeSiNoHabiaEstadoDeFallosQueLimpiar() {
        User user = conFallosPrevios(0);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        doThrow(ApiException.badRequest("Código incorrecto, intenta de nuevo"))
                .when(authService).disable2fa(any(), anyString());

        assertThatThrownBy(() -> controller.disable2fa(
                principal(), body("000000"), mock(HttpServletRequest.class)))
                .isInstanceOf(ApiException.class);

        // Sin nada que resetear no hay UPDATE: el camino feliz no debe generar
        // una escritura por request.
        verify(userRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void cuentaElFalloYBloqueaAlLlegarAlUmbral() {
        User user = conFallosPrevios(4); // el próximo fallo es el quinto
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> controller.disable2fa(
                principal(), body("000000"), mock(HttpServletRequest.class)))
                .isInstanceOf(ApiException.class);

        verify(userRepository).save(user);
        assertThat(user.getFailedPasswordAttempts()).isEqualTo(5);
        assertThat(user.getPasswordLockedUntil()).isAfter(LocalDateTime.now());
    }
}
