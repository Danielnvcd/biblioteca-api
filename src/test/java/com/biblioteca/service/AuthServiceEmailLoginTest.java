package com.biblioteca.service;

import com.biblioteca.dto.LoginRequest;
import com.biblioteca.dto.VerifyEmailCodeRequest;
import com.biblioteca.exception.ApiException;
import com.biblioteca.model.EmailCode;
import com.biblioteca.model.User;
import com.biblioteca.repository.UserRepository;
import com.biblioteca.security.AccessTokenDenylistService;
import com.biblioteca.security.EncryptionService;
import com.biblioteca.security.JwtTokenProvider;
import com.biblioteca.security.RefreshTokenService;
import com.biblioteca.security.TotpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El segundo factor por correo dentro del flujo de login.
 *
 * Lo central que se protege acá: el código por correo NUNCA es un factor
 * único. Solo se emite y se verifica contra un step token de scope
 * 2fa-pending — el que /login entrega recién después de validar la
 * contraseña. Sin ese token no se puede pedir un código para ninguna cuenta,
 * que es lo que impide usar el endpoint como cañón de correo o como vía de
 * fuerza bruta sin conocer la contraseña.
 */
class AuthServiceEmailLoginTest {

    private static final String HASH = "$2a$12$realhashrealhashrealhashre";
    private static final String STEP = "step-token";

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private JwtTokenProvider tokenProvider;
    private EmailCodeService emailCodeService;
    private LoginAlertService loginAlertService;
    private AuthService authService;

    private static final AuthService.LoginContext CTX =
            new AuthService.LoginContext("1.1.1.1", "ua", null);

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        tokenProvider = mock(JwtTokenProvider.class);
        emailCodeService = mock(EmailCodeService.class);
        loginAlertService = mock(LoginAlertService.class);

        when(passwordEncoder.encode(any())).thenReturn("$2a$12$dummydummydummydummydum");
        when(loginAlertService.registerDevice(any(), any(), any(), any()))
                .thenReturn(new LoginAlertService.DeviceCheck("cookie", false, "Chrome"));
        when(emailCodeService.issueAndSend(any(), any(), anyString(), any()))
                .thenReturn(new EmailCodeService.Issued(LocalDateTime.now().plusMinutes(10), "an••••@maxipet.com"));

        authService = new AuthService(userRepository, passwordEncoder, tokenProvider,
                mock(TotpService.class), mock(RefreshTokenService.class),
                mock(EncryptionService.class), mock(AccessTokenDenylistService.class),
                emailCodeService, loginAlertService,
                mock(MailService.class), mock(EmailTemplates.class));
    }

    private static User base() {
        User u = new User();
        u.setId(7);
        u.setUsername("ana");
        u.setPasswordHash(HASH);
        u.setRole("user");
        return u;
    }

    private static User conCorreo() {
        User u = base();
        u.setEmail("ana@maxipet.com");
        u.setEmailVerified(true);
        u.setEmail2faEnabled(true);
        return u;
    }

    private void passwordOk(User u) {
        when(userRepository.findByUsername("ana")).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("correcta", HASH)).thenReturn(true);
        when(tokenProvider.generate2faStepToken(eq(7), eq("ana"), any(Boolean.class))).thenReturn(STEP);
        when(tokenProvider.generateToken(7, "ana", "user")).thenReturn("access-token");
    }

    private void stepTokenValido() {
        when(tokenProvider.validateToken(STEP)).thenReturn(true);
        when(tokenProvider.getScopeFromToken(STEP)).thenReturn("2fa-pending");
        when(tokenProvider.getUserIdFromToken(STEP)).thenReturn(7);
    }

    private static LoginRequest request() {
        LoginRequest r = new LoginRequest();
        r.setUsername("ana");
        r.setPassword("correcta");
        return r;
    }

    // ─── /login ─────────────────────────────────────────────────────────────

    @Test
    void conSoloCorreoElCodigoSeMandaSolo() {
        User u = conCorreo();
        passwordOk(u);

        var result = authService.login(request(), CTX);

        assertThat(result.body().isRequires2fa()).isTrue();
        assertThat(result.body().getMethods()).containsExactly("email");
        assertThat(result.body().isCodeSent()).isTrue();
        // Enmascarado: a esta pantalla se llega sabiendo solo la contraseña.
        assertThat(result.body().getMaskedEmail()).isEqualTo("an••••@maxipet.com");
        verify(emailCodeService).issueAndSend(eq(u), eq(EmailCode.Purpose.LOGIN),
                eq("ana@maxipet.com"), eq("1.1.1.1"));
    }

    @Test
    void conTotpTambienActivoElCorreoNoSeDisparaSolo() {
        User u = conCorreo();
        u.setTotpSecret("gcm:secret");
        passwordOk(u);

        var result = authService.login(request(), CTX);

        assertThat(result.body().getMethods()).containsExactly("totp", "email");
        assertThat(result.body().isCodeSent()).isFalse();
        // La mayoría va a usar la app; mandar un correo en cada login sería
        // ruido. Queda a un clic en la pantalla de verificación.
        verify(emailCodeService, never()).issueAndSend(any(), any(), anyString(), any());
    }

    @Test
    void siElEnvioFallaElLoginSigueEnPieComoPendiente() {
        User u = conCorreo();
        passwordOk(u);
        when(emailCodeService.issueAndSend(any(), any(), anyString(), any()))
                .thenThrow(ApiException.tooManyRequests("Esperá 30 segundos"));

        var result = authService.login(request(), CTX);

        // No se convierte en un 500: el usuario llega a la pantalla y puede
        // reintentar el envío, donde sí ve el motivo real.
        assertThat(result.body().isRequires2fa()).isTrue();
        assertThat(result.body().isCodeSent()).isFalse();
    }

    @Test
    void unFalloInesperadoAlEmitirTampocoRompeElLogin() {
        User u = conCorreo();
        passwordOk(u);
        // No es una ApiException: es lo que tiraría la base si la escritura de
        // email_codes falla. Un login cuya contraseña ya se validó no puede
        // terminar en 500 por esto.
        when(emailCodeService.issueAndSend(any(), any(), anyString(), any()))
                .thenThrow(new RuntimeException("conexión perdida"));

        var result = authService.login(request(), CTX);

        assertThat(result.body().isRequires2fa()).isTrue();
        assertThat(result.body().isCodeSent()).isFalse();
    }

    @Test
    void sinCorreoVerificadoElFactorNoCuenta() {
        User u = base();
        u.setEmail("ana@maxipet.com");
        u.setEmail2faEnabled(true);
        u.setEmailVerified(false); // nadie probó que lee ese buzón
        passwordOk(u);

        var result = authService.login(request(), CTX);

        assertThat(result.body().isRequires2fa()).isFalse();
        assertThat(result.body().getToken()).isEqualTo("access-token");
    }

    // ─── /verify-email-code ─────────────────────────────────────────────────

    @Test
    void elCodigoCorrectoAbreSesion() {
        User u = conCorreo();
        stepTokenValido();
        when(userRepository.findById(7)).thenReturn(Optional.of(u));
        when(tokenProvider.generateToken(7, "ana", "user")).thenReturn("access-token");

        var result = authService.verifyEmailCode(req("12345678"), CTX);

        assertThat(result.body().getToken()).isEqualTo("access-token");
        verify(emailCodeService).verifyAndConsume(u, EmailCode.Purpose.LOGIN, "12345678");
    }

    @Test
    void unTokenDeAccesoNoSirveComoStepToken() {
        when(tokenProvider.validateToken(STEP)).thenReturn(true);
        when(tokenProvider.getScopeFromToken(STEP)).thenReturn("access");

        // Sin el chequeo de scope, un access token corriente completaría el
        // segundo factor de su propio dueño y el paso dejaría de probar nada.
        assertThatThrownBy(() -> authService.verifyEmailCode(req("12345678"), CTX))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Token inválido");

        verify(emailCodeService, never()).verifyAndConsume(any(), any(), anyString());
    }

    @Test
    void noSePuedePedirCodigoSiElFactorNoEstaActivo() {
        User u = base(); // sin correo configurado
        stepTokenValido();
        when(userRepository.findById(7)).thenReturn(Optional.of(u));

        assertThatThrownBy(() -> authService.requestLoginCode(STEP, "1.1.1.1"))
                .isInstanceOf(ApiException.class);

        verify(emailCodeService, never()).issueAndSend(any(), any(), anyString(), any());
    }

    @Test
    void elDestinatarioSaleDelTokenYNoDeLaPeticion() {
        User u = conCorreo();
        stepTokenValido();
        when(userRepository.findById(7)).thenReturn(Optional.of(u));

        authService.requestLoginCode(STEP, "1.1.1.1");

        // El correo va a la dirección de la cuenta que identifica el token.
        // No hay ninguna vía por la que quien llama pueda elegir el destino.
        verify(emailCodeService).issueAndSend(eq(u), eq(EmailCode.Purpose.LOGIN),
                eq("ana@maxipet.com"), eq("1.1.1.1"));
    }

    private static VerifyEmailCodeRequest req(String code) {
        VerifyEmailCodeRequest r = new VerifyEmailCodeRequest();
        r.setStepToken(STEP);
        r.setCode(code);
        return r;
    }
}
