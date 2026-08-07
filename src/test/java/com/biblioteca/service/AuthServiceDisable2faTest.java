package com.biblioteca.service;

import com.biblioteca.exception.ApiException;
import com.biblioteca.model.User;
import com.biblioteca.repository.UserRepository;
import com.biblioteca.security.EncryptionService;
import com.biblioteca.security.JwtTokenProvider;
import com.biblioteca.security.RefreshTokenService;
import com.biblioteca.security.TotpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cubre la desactivación de 2FA, que hasta ahora no existía: una vez activado
 * no había forma de quitarlo ni para el dueño de la cuenta ni para un admin.
 *
 * Lo que se protege acá es que el código TOTP sea obligatorio de verdad. Si
 * bastara la contraseña, un access token robado alcanzaría para quitarle el
 * segundo factor a la cuenta — justo lo que el segundo factor debe impedir.
 */
class AuthServiceDisable2faTest {

    private static final String CIFRADO = "enc:JBSWY3DPEHPK3PXP";
    private static final String PLANO   = "JBSWY3DPEHPK3PXP";

    private UserRepository userRepository;
    private TotpService totpService;
    private EncryptionService encryptionService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        totpService = mock(TotpService.class);
        encryptionService = mock(EncryptionService.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.encode(any())).thenReturn("$2a$12$dummydummydummydummydum");

        authService = new AuthService(userRepository, passwordEncoder,
                mock(JwtTokenProvider.class), totpService,
                mock(RefreshTokenService.class), encryptionService,
                mock(com.biblioteca.security.AccessTokenDenylistService.class));
    }

    private static User conTotp() {
        User u = new User();
        u.setId(7);
        u.setUsername("ana");
        u.setTotpSecret(CIFRADO);
        return u;
    }

    @Test
    void codigoValidoLimpiaElSecreto() {
        User u = conTotp();
        when(encryptionService.decrypt(CIFRADO)).thenReturn(PLANO);
        when(totpService.verify(PLANO, "123456")).thenReturn(true);

        authService.disable2fa(u, "123456");

        assertThat(u.getTotpSecret()).isNull();
        verify(userRepository).save(u);
    }

    @Test
    void codigoInvalidoNoDesactivaNada() {
        User u = conTotp();
        when(encryptionService.decrypt(CIFRADO)).thenReturn(PLANO);
        when(totpService.verify(PLANO, "000000")).thenReturn(false);

        assertThatThrownBy(() -> authService.disable2fa(u, "000000"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Código incorrecto");

        // Lo importante: el secreto sigue en su lugar.
        assertThat(u.getTotpSecret()).isEqualTo(CIFRADO);
        // Sí se persiste, pero solo el contador de fallos: el fallo tiene que
        // dejar rastro para que el lockout por cuenta pueda contarlo. Antes acá
        // se afirmaba que no se guardaba nada, y por eso los intentos de código
        // eran ilimitados. Ver AuthServiceTotpLockoutTest.
        assertThat(u.getFailedTotpAttempts()).isEqualTo(1);
        verify(userRepository).save(u);
    }

    @Test
    void elSecretoSeVerificaDescifradoNoComoEstaGuardado() {
        User u = conTotp();
        when(encryptionService.decrypt(CIFRADO)).thenReturn(PLANO);
        when(totpService.verify(PLANO, "123456")).thenReturn(true);

        authService.disable2fa(u, "123456");

        // Si se le pasara el valor cifrado a TotpService, ningún código real
        // validaría jamás y la desactivación quedaría rota en producción.
        verify(totpService).verify(PLANO, "123456");
        verify(totpService, never()).verify(CIFRADO, "123456");
    }

    @Test
    void sinTotpActivoAvisaEnLugarDeFallarRaro() {
        User u = new User();
        u.setId(7);
        u.setTotpSecret(null);

        assertThatThrownBy(() -> authService.disable2fa(u, "123456"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no está activa");

        verify(userRepository, never()).save(any());
    }

    @Test
    void secretoVacioCuentaComoNoActivo() {
        // Los registros heredados de la app anterior guardaban "" en vez de NULL.
        User u = new User();
        u.setId(7);
        u.setTotpSecret("");

        assertThatThrownBy(() -> authService.disable2fa(u, "123456"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no está activa");
    }

    @Test
    void elResetAdministrativoNoPideCodigo() {
        // Es el caso "perdí el teléfono": el dueño legítimo tampoco puede
        // generar un código, así que este camino no lo exige. El control de
        // permisos vive en el controller (solo super_admin).
        User u = conTotp();

        authService.clear2fa(u);

        assertThat(u.getTotpSecret()).isNull();
        verify(userRepository).save(u);
        verify(totpService, never()).verify(any(), any());
    }
}
