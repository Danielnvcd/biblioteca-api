package com.biblioteca.service;

import com.biblioteca.dto.Verify2faRequest;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bloqueo por cuenta en la verificación del código TOTP.
 *
 * Antes de estos cambios, /verify-2fa no tocaba ningún contador: un código
 * incorrecto lanzaba 401 y no dejaba rastro en la cuenta. El único techo era
 * el bucket por IP de RateLimitFilter (8/min), y un techo por IP no cubre a
 * un atacante que se presenta desde varias — que es exactamente el motivo por
 * el que el login por contraseña ya tenía un bloqueo por cuenta desde V6.
 *
 * Consecuencia: quien tuviera la contraseña podía recorrer el espacio de
 * códigos hasta acertar, y el segundo factor no agregaba nada frente al
 * escenario para el que existe (contraseña filtrada o phishing).
 *
 * También se cubre acá que el secret que se activa en /confirm-2fa sea el que
 * emitió el servidor y no uno recibido del cliente.
 */
class AuthServiceTotpLockoutTest {

    private static final String CIFRADO = "gcm:iv:JBSWY3DPEHPK3PXP";
    private static final String PLANO   = "JBSWY3DPEHPK3PXP";
    private static final String STEP    = "step-token";

    private UserRepository userRepository;
    private TotpService totpService;
    private EncryptionService encryptionService;
    private JwtTokenProvider tokenProvider;
    private RefreshTokenService refreshTokenService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        totpService = mock(TotpService.class);
        encryptionService = mock(EncryptionService.class);
        tokenProvider = mock(JwtTokenProvider.class);
        refreshTokenService = mock(RefreshTokenService.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.encode(any())).thenReturn("$2a$12$dummydummydummydummydum");

        authService = new AuthService(userRepository, passwordEncoder, tokenProvider,
                totpService, refreshTokenService, encryptionService,
                mock(com.biblioteca.security.AccessTokenDenylistService.class));
    }

    private User conTotp() {
        User u = new User();
        u.setId(7);
        u.setUsername("ana");
        u.setRole("super_admin");
        u.setTotpSecret(CIFRADO);
        return u;
    }

    /** Prepara el camino feliz del step token para el userId 7. */
    private void stepTokenValido() {
        when(tokenProvider.validateToken(STEP)).thenReturn(true);
        when(tokenProvider.getScopeFromToken(STEP)).thenReturn("2fa-pending");
        when(tokenProvider.getUserIdFromToken(STEP)).thenReturn(7);
    }

    private static Verify2faRequest req(String code) {
        Verify2faRequest r = new Verify2faRequest();
        r.setStepToken(STEP);
        r.setCode(code);
        return r;
    }

    // ─── /verify-2fa ────────────────────────────────────────────────────────

    @Test
    void codigoIncorrectoSumaUnFalloYLoPersiste() {
        User u = conTotp();
        stepTokenValido();
        when(userRepository.findById(7)).thenReturn(Optional.of(u));
        when(encryptionService.decrypt(CIFRADO)).thenReturn(PLANO);
        when(totpService.verify(PLANO, "000000")).thenReturn(false);

        assertThatThrownBy(() -> authService.verify2fa(req("000000"), "1.1.1.1", "ua"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Código incorrecto");

        // El punto del arreglo: el fallo deja estado en la cuenta. Sin esto el
        // contador nunca crece y no existe techo alguno.
        assertThat(u.getFailedTotpAttempts()).isEqualTo(1);
        assertThat(u.getTotpLockedUntil()).isNull();
        verify(userRepository).save(u);
    }

    @Test
    void quintoFalloConsecutivoBloqueaLaCuenta() {
        User u = conTotp();
        u.setFailedTotpAttempts(4);
        stepTokenValido();
        when(userRepository.findById(7)).thenReturn(Optional.of(u));
        when(encryptionService.decrypt(CIFRADO)).thenReturn(PLANO);
        when(totpService.verify(PLANO, "000000")).thenReturn(false);

        assertThatThrownBy(() -> authService.verify2fa(req("000000"), "1.1.1.1", "ua"))
                .isInstanceOf(ApiException.class);

        assertThat(u.getFailedTotpAttempts()).isEqualTo(5);
        assertThat(u.getTotpLockedUntil()).isAfter(LocalDateTime.now().plusMinutes(14));
    }

    @Test
    void cuentaBloqueadaRechazaInclusoElCodigoCorrecto() {
        User u = conTotp();
        u.setFailedTotpAttempts(5);
        u.setTotpLockedUntil(LocalDateTime.now().plusMinutes(10));
        stepTokenValido();
        when(userRepository.findById(7)).thenReturn(Optional.of(u));

        assertThatThrownBy(() -> authService.verify2fa(req("123456"), "1.1.1.1", "ua"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);

        // Cortocircuito real: durante el bloqueo no se verifica ningún código,
        // así que el endpoint tampoco sirve como oráculo mientras dura.
        verify(totpService, never()).verify(any(), any());
        verify(refreshTokenService, never()).issue(any(), any(), any());
    }

    @Test
    void elBloqueoSeVenceSoloYDejaEntrar() {
        User u = conTotp();
        u.setFailedTotpAttempts(5);
        u.setTotpLockedUntil(LocalDateTime.now().minusSeconds(1));
        stepTokenValido();
        when(userRepository.findById(7)).thenReturn(Optional.of(u));
        when(encryptionService.decrypt(CIFRADO)).thenReturn(PLANO);
        when(totpService.verify(PLANO, "123456")).thenReturn(true);
        when(tokenProvider.generateToken(7, "ana", "super_admin")).thenReturn("token-ok");

        var result = authService.verify2fa(req("123456"), "1.1.1.1", "ua");

        // Que se venza solo importa: un lockout permanente activable por un
        // tercero sería en sí un vector de denegación de servicio.
        assertThat(result.body().getToken()).isEqualTo("token-ok");
        assertThat(u.getFailedTotpAttempts()).isZero();
        assertThat(u.getTotpLockedUntil()).isNull();
    }

    @Test
    void codigoCorrectoLimpiaLosFallosPrevios() {
        User u = conTotp();
        u.setFailedTotpAttempts(3);
        stepTokenValido();
        when(userRepository.findById(7)).thenReturn(Optional.of(u));
        when(encryptionService.decrypt(CIFRADO)).thenReturn(PLANO);
        when(totpService.verify(PLANO, "123456")).thenReturn(true);
        when(tokenProvider.generateToken(7, "ana", "super_admin")).thenReturn("token-ok");

        authService.verify2fa(req("123456"), "1.1.1.1", "ua");

        // Sin reset, un usuario despistado acumularía fallos entre logins
        // legítimos y acabaría bloqueado sin haber sido atacado.
        assertThat(u.getFailedTotpAttempts()).isZero();
    }

    @Test
    void losFallosSobrevivenAUnStepTokenNuevo() {
        // El escenario que hace que el techo por IP no alcance: el atacante ya
        // tiene la contraseña, así que puede pedir un step token nuevo cuantas
        // veces quiera. El contador vive en la cuenta, no en el token, así que
        // renovarlo no lo reinicia.
        User u = conTotp();
        u.setFailedTotpAttempts(4);
        stepTokenValido();
        when(userRepository.findById(7)).thenReturn(Optional.of(u));
        when(encryptionService.decrypt(CIFRADO)).thenReturn(PLANO);
        when(totpService.verify(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> authService.verify2fa(req("000000"), "1.1.1.1", "ua"))
                .isInstanceOf(ApiException.class);
        assertThat(u.getTotpLockedUntil()).isNotNull();

        // Segundo intento con OTRO step token: sigue bloqueado.
        String otroStep = "step-token-2";
        when(tokenProvider.validateToken(otroStep)).thenReturn(true);
        when(tokenProvider.getScopeFromToken(otroStep)).thenReturn("2fa-pending");
        when(tokenProvider.getUserIdFromToken(otroStep)).thenReturn(7);
        Verify2faRequest r = new Verify2faRequest();
        r.setStepToken(otroStep);
        r.setCode("111111");

        assertThatThrownBy(() -> authService.verify2fa(r, "9.9.9.9", "ua"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ─── /confirm-2fa: el secret lo pone el servidor ────────────────────────

    @Test
    void setupGuardaElSecretPendienteCifrado() {
        User u = new User();
        u.setId(7);
        when(totpService.newSecret()).thenReturn(PLANO);
        when(encryptionService.encrypt(PLANO)).thenReturn(CIFRADO);

        String devuelto = authService.setup2fa(u);

        // Se le devuelve en claro al usuario (para el QR) pero se guarda cifrado.
        assertThat(devuelto).isEqualTo(PLANO);
        assertThat(u.getTotpPendingSecret()).isEqualTo(CIFRADO);
        assertThat(u.getTotpSecret()).isNull(); // aún no está activo
        verify(userRepository).save(u);
    }

    @Test
    void confirmActivaElSecretDelServidorNoUnoDelCliente() {
        User u = new User();
        u.setId(7);
        u.setTotpPendingSecret(CIFRADO);
        when(encryptionService.decrypt(CIFRADO)).thenReturn(PLANO);
        when(totpService.verify(PLANO, "123456")).thenReturn(true);

        authService.verifyAndEnable2fa(u, "123456");

        // El secret activo es exactamente el que emitió setup2fa, ya cifrado.
        // Si se volviera a cifrar acá quedaría indescifrable en el siguiente login.
        assertThat(u.getTotpSecret()).isEqualTo(CIFRADO);
        assertThat(u.getTotpPendingSecret()).isNull();
        verify(encryptionService, never()).encrypt(any());
    }

    @Test
    void confirmSinPendienteNoActivaNada() {
        // Antes el secret venía en el body, así que este camino simplemente no
        // existía: se podía activar un factor sin haber pasado por /setup-2fa.
        User u = new User();
        u.setId(7);
        u.setTotpPendingSecret(null);

        assertThatThrownBy(() -> authService.verifyAndEnable2fa(u, "123456"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("pendiente");

        assertThat(u.getTotpSecret()).isNull();
        verify(totpService, never()).verify(any(), any());
    }

    @Test
    void confirmComparteElMismoLockout() {
        // Sin esto, /confirm-2fa quedaba como vía alternativa para probar
        // códigos sin límite mientras hubiera un pendiente.
        User u = new User();
        u.setId(7);
        u.setTotpPendingSecret(CIFRADO);
        u.setFailedTotpAttempts(4);
        when(encryptionService.decrypt(CIFRADO)).thenReturn(PLANO);
        when(totpService.verify(PLANO, "000000")).thenReturn(false);

        assertThatThrownBy(() -> authService.verifyAndEnable2fa(u, "000000"))
                .isInstanceOf(ApiException.class);

        assertThat(u.getFailedTotpAttempts()).isEqualTo(5);
        assertThat(u.getTotpLockedUntil()).isNotNull();
    }

    // ─── /disable-2fa ───────────────────────────────────────────────────────

    @Test
    void disableComparteElMismoLockout() {
        User u = conTotp();
        u.setFailedTotpAttempts(5);
        u.setTotpLockedUntil(LocalDateTime.now().plusMinutes(10));

        assertThatThrownBy(() -> authService.disable2fa(u, "123456"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(u.getTotpSecret()).isEqualTo(CIFRADO);
        verify(totpService, never()).verify(any(), any());
    }

    @Test
    void elResetAdministrativoDesbloquea() {
        // Si el reseteo existe para destrabar a quien perdió el dispositivo,
        // dejarlo bloqueado lo obligaría a esperar 15 min para reconfigurar.
        User u = conTotp();
        u.setFailedTotpAttempts(5);
        u.setTotpLockedUntil(LocalDateTime.now().plusMinutes(10));
        u.setTotpPendingSecret(CIFRADO);

        authService.clear2fa(u);

        assertThat(u.getTotpSecret()).isNull();
        assertThat(u.getTotpPendingSecret()).isNull();
        assertThat(u.getFailedTotpAttempts()).isZero();
        assertThat(u.getTotpLockedUntil()).isNull();
    }
}
