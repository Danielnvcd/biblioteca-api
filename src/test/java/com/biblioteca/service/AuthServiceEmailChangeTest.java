package com.biblioteca.service;

import com.biblioteca.dto.EmailPreferencesRequest;
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
 * Alta, confirmación y baja del correo de la cuenta.
 *
 * Lo que se protege: que una dirección no sirva para nada hasta que alguien
 * pruebe que la lee, y que el dueño real se entere si se la cambian. Sin lo
 * primero, quien robe un access token se asigna un correo propio y se queda
 * con el segundo factor; sin lo segundo, ese cambio ocurre en silencio.
 */
class AuthServiceEmailChangeTest {

    private UserRepository userRepository;
    private EmailCodeService emailCodeService;
    private MailService mailService;
    private EmailTemplates templates;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        emailCodeService = mock(EmailCodeService.class);
        mailService = mock(MailService.class);
        templates = mock(EmailTemplates.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.encode(any())).thenReturn("$2a$12$dummydummydummydummydum");
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(emailCodeService.issueAndSend(any(), any(), anyString(), any()))
                .thenReturn(new EmailCodeService.Issued(LocalDateTime.now().plusMinutes(10), "nu••••@maxipet.com"));

        authService = new AuthService(userRepository, passwordEncoder,
                mock(JwtTokenProvider.class), mock(TotpService.class),
                mock(RefreshTokenService.class), mock(EncryptionService.class),
                mock(AccessTokenDenylistService.class),
                emailCodeService, mock(LoginAlertService.class), mailService, templates);
    }

    private static User user() {
        User u = new User();
        u.setId(7);
        u.setUsername("ana");
        return u;
    }

    // ─── Alta ───────────────────────────────────────────────────────────────

    @Test
    void elAltaDejaLaDireccionPendienteYNoLaActiva() {
        User u = user();

        authService.startEmailChange(u, "  Nueva@Maxipet.COM ", "1.1.1.1");

        // Normalizada y guardada como pendiente, nunca como correo de la cuenta.
        assertThat(u.getPendingEmail()).isEqualTo("nueva@maxipet.com");
        assertThat(u.getEmail()).isNull();
        assertThat(u.isEmailVerified()).isFalse();
        verify(emailCodeService).issueAndSend(eq(u), eq(EmailCode.Purpose.VERIFY_EMAIL),
                eq("nueva@maxipet.com"), eq("1.1.1.1"));
    }

    @Test
    void unaDireccionInvalidaSeRechazaAntesDeMandarNada() {
        assertThatThrownBy(() -> authService.startEmailChange(user(), "no-es-un-correo", "1.1.1.1"))
                .isInstanceOf(ApiException.class);

        verify(emailCodeService, never()).issueAndSend(any(), any(), anyString(), any());
    }

    @Test
    void unCorreoYaUsadoPorOtraCuentaSeRechazaSinDecirPorQue() {
        User otro = user();
        otro.setId(99);
        when(userRepository.findByEmail("ocupado@maxipet.com")).thenReturn(Optional.of(otro));

        assertThatThrownBy(() -> authService.startEmailChange(user(), "ocupado@maxipet.com", "1.1.1.1"))
                .isInstanceOf(ApiException.class)
                // Un "ese correo ya está en uso" sería un oráculo para saber
                // quién tiene cuenta en el sistema.
                .hasMessageContaining("No se puede usar ese correo");
    }

    // ─── Confirmación ───────────────────────────────────────────────────────

    @Test
    void confirmarPromueveLaDireccionQueRecibioElCodigo() {
        User u = user();
        u.setPendingEmail("nueva@maxipet.com");
        when(emailCodeService.verifyAndConsume(u, EmailCode.Purpose.VERIFY_EMAIL, "12345678"))
                .thenReturn("nueva@maxipet.com");

        authService.confirmEmailChange(u, "12345678");

        assertThat(u.getEmail()).isEqualTo("nueva@maxipet.com");
        assertThat(u.isEmailVerified()).isTrue();
        assertThat(u.getPendingEmail()).isNull();
    }

    @Test
    void alCambiarDeCorreoSeAvisaALaDireccionANTERIOR() {
        User u = user();
        u.setEmail("vieja@maxipet.com");
        u.setEmailVerified(true);
        u.setPendingEmail("nueva@maxipet.com");
        when(emailCodeService.verifyAndConsume(any(), any(), anyString()))
                .thenReturn("nueva@maxipet.com");

        authService.confirmEmailChange(u, "12345678");

        // Es la señal que delata un secuestro: quien entra a una cuenta ajena
        // mueve el correo primero, y si el aviso fuera solo a la dirección
        // nueva el dueño real no se enteraría nunca.
        verify(mailService).sendAsync(eq("vieja@maxipet.com"), any());
    }

    @Test
    void sinNadaPendienteNoSeVerificaNingunCodigo() {
        assertThatThrownBy(() -> authService.confirmEmailChange(user(), "12345678"))
                .isInstanceOf(ApiException.class);

        verify(emailCodeService, never()).verifyAndConsume(any(), any(), anyString());
    }

    // ─── Baja ───────────────────────────────────────────────────────────────

    @Test
    void quitarElCorreoApagaTambienElSegundoFactor() {
        User u = user();
        u.setEmail("ana@maxipet.com");
        u.setEmailVerified(true);
        u.setEmail2faEnabled(true);
        u.setFailedEmailCodeAttempts(3);
        u.setEmailCodeLockedUntil(LocalDateTime.now().plusMinutes(5));

        authService.removeEmail(u);

        // Dejar el factor activo sin canal dejaría al usuario fuera de su cuenta.
        assertThat(u.isEmail2faEnabled()).isFalse();
        assertThat(u.getEmail()).isNull();
        assertThat(u.isEmailVerified()).isFalse();
        // Y el bloqueo se limpia: si no, quien vuelva a dar de alta un correo
        // se encuentra bloqueado por intentos de un canal que ya no existe.
        assertThat(u.getFailedEmailCodeAttempts()).isZero();
        assertThat(u.getEmailCodeLockedUntil()).isNull();
        verify(mailService).sendAsync(eq("ana@maxipet.com"), any());
    }

    // ─── Reseteo administrativo ─────────────────────────────────────────────

    @Test
    void elReseteoDe2faDestrabaTambienElFactorPorCorreo() {
        User u = user();
        u.setTotpSecret("gcm:secret");
        u.setEmail("ana@maxipet.com");
        u.setEmailVerified(true);
        u.setEmail2faEnabled(true);
        u.setEmailCodeLockedUntil(LocalDateTime.now().plusMinutes(10));

        authService.clear2fa(u);

        // Si el reseteo dejara el código por correo activo, quien perdiera el
        // acceso a su buzón quedaría fuera de su cuenta sin vía de rescate —
        // el mismo callejón sin salida que este reseteo existe para evitar.
        assertThat(u.isEmail2faEnabled()).isFalse();
        assertThat(u.getTotpSecret()).isNull();
        assertThat(u.getEmailCodeLockedUntil()).isNull();
        // El correo se conserva: después de un reseteo por sospecha, los avisos
        // son justamente lo que conviene mantener encendido.
        assertThat(u.getEmail()).isEqualTo("ana@maxipet.com");
        assertThat(u.isEmailVerified()).isTrue();
    }

    // ─── Preferencias ───────────────────────────────────────────────────────

    @Test
    void noSePuedeActivarElFactorSinCorreoConfirmado() {
        User u = user();
        u.setEmail("ana@maxipet.com");
        u.setEmailVerified(false);

        EmailPreferencesRequest body = new EmailPreferencesRequest();
        body.setEmail2faEnabled(true);

        assertThatThrownBy(() -> authService.updateEmailPreferences(u, body))
                .isInstanceOf(ApiException.class);

        assertThat(u.isEmail2faEnabled()).isFalse();
    }

    @Test
    void apagarLosAvisosMandaUnUltimoAviso() {
        User u = user();
        u.setEmail("ana@maxipet.com");
        u.setEmailVerified(true);
        u.setLoginAlerts("new_device");

        EmailPreferencesRequest body = new EmailPreferencesRequest();
        body.setLoginAlerts("off");

        authService.updateEmailPreferences(u, body);

        // Silenciar la notificación es el primer movimiento de quien entró a
        // una cuenta ajena. Sin este mensaje sería el único cambio de seguridad
        // que no se anuncia.
        verify(mailService).sendAsync(eq("ana@maxipet.com"), any());
    }

    @Test
    void volverAApagarAvisosYaApagadosNoMandaNada() {
        User u = user();
        u.setEmail("ana@maxipet.com");
        u.setEmailVerified(true);
        u.setLoginAlerts("off");

        EmailPreferencesRequest body = new EmailPreferencesRequest();
        body.setLoginAlerts("off");

        authService.updateEmailPreferences(u, body);

        // Si no, repetir la llamada sería una vía para inundar el buzón.
        verify(mailService, never()).sendAsync(anyString(), any());
    }

    @Test
    void subirElNivelDeAvisosNoMandaNada() {
        User u = user();
        u.setEmail("ana@maxipet.com");
        u.setEmailVerified(true);
        u.setLoginAlerts("new_device");

        EmailPreferencesRequest body = new EmailPreferencesRequest();
        body.setLoginAlerts("always");

        authService.updateEmailPreferences(u, body);

        verify(mailService, never()).sendAsync(anyString(), any());
    }

    @Test
    void unaCuentaDeAdministracionNoPuedeUsarElCorreoComoSegundoFactor() {
        User u = user();
        u.setRole("super_admin");
        u.setEmail("ana@maxipet.com");
        u.setEmailVerified(true);

        EmailPreferencesRequest body = new EmailPreferencesRequest();
        body.setEmail2faEnabled(true);

        // Con dos métodos alternativos, la cuenta vale lo que el más débil de
        // los dos — y un buzón se compromete mucho más fácil que un secreto
        // TOTP. En estas cuentas eso equivaldría a entregar el sistema entero.
        assertThatThrownBy(() -> authService.updateEmailPreferences(u, body))
                .isInstanceOf(ApiException.class);

        assertThat(u.isEmail2faEnabled()).isFalse();
    }

    @Test
    void esasCuentasSiPuedenUsarElCorreoParaAvisos() {
        User u = user();
        u.setRole("super_admin");
        u.setEmail("ana@maxipet.com");
        u.setEmailVerified(true);

        EmailPreferencesRequest body = new EmailPreferencesRequest();
        body.setLoginAlerts("always");

        authService.updateEmailPreferences(u, body);

        // La restricción es sobre el factor de acceso, no sobre el correo: los
        // avisos son puro beneficio y no abren ninguna vía de entrada.
        assertThat(u.getLoginAlerts()).isEqualTo("always");
    }

    @Test
    void unCampoNuloSignificaNoTocar() {
        User u = user();
        u.setEmail("ana@maxipet.com");
        u.setEmailVerified(true);
        u.setEmail2faEnabled(true);
        u.setLoginAlerts("always");

        EmailPreferencesRequest body = new EmailPreferencesRequest();
        body.setLoginAlerts("off"); // solo se movió este control

        authService.updateEmailPreferences(u, body);

        assertThat(u.getLoginAlerts()).isEqualTo("off");
        // Sin esta regla, guardar la preferencia de avisos apagaría el segundo
        // factor de rebote.
        assertThat(u.isEmail2faEnabled()).isTrue();
    }
}
