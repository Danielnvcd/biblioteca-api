package com.biblioteca.service;

import com.biblioteca.exception.ApiException;
import com.biblioteca.model.EmailCode;
import com.biblioteca.model.User;
import com.biblioteca.repository.EmailCodeRepository;
import com.biblioteca.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Las defensas contra fuerza bruta del código por correo.
 *
 * Un código numérico enviado por correo es un secreto corto por definición, y
 * el techo por IP de RateLimitFilter no cubre a un atacante distribuido — el
 * mismo razonamiento de V6 y V7. Lo que hace que el factor sirva es lo que se
 * verifica acá: intentos por código, bloqueo por cuenta, techo de emisión,
 * consumo atómico y que el código no viva en claro en la base.
 */
class EmailCodeServiceTest {

    /** Los 32 bytes del fallback de dev — sirve para HMAC en test. */
    private static final String KEY = "Zm9yLWRldi1vbmx5LWRvLW5vdC11c2UtaW4tcHJvZCE=";
    private static final String DEST = "ana@maxipet.com";

    private EmailCodeRepository codeRepository;
    private UserRepository userRepository;
    private MailService mailService;
    private EmailTemplates templates;
    private EmailCodeService service;

    @BeforeEach
    void setUp() {
        codeRepository = mock(EmailCodeRepository.class);
        userRepository = mock(UserRepository.class);
        mailService = mock(MailService.class);
        templates = mock(EmailTemplates.class);

        when(mailService.isEnabled()).thenReturn(true);
        when(mailService.send(anyString(), any())).thenReturn(true);
        when(codeRepository.lastIssuedAt(any(), anyString())).thenReturn(Optional.empty());
        when(codeRepository.countIssuedSince(any(), anyString(), any())).thenReturn(0L);
        when(codeRepository.save(any(EmailCode.class))).thenAnswer(inv -> inv.getArgument(0));
        // Por defecto queda cupo de intentos; los tests que miran el techo lo
        // sobrescriben para devolver 0.
        when(codeRepository.reserveAttempt(any(), org.mockito.ArgumentMatchers.anyShort())).thenReturn(1);

        service = new EmailCodeService(codeRepository, userRepository, mailService, templates, KEY);
    }

    private static User user() {
        User u = new User();
        u.setId(7);
        u.setUsername("ana");
        u.setEmail(DEST);
        u.setEmailVerified(true);
        return u;
    }

    /**
     * Emite un código de verdad y devuelve el par (texto plano, entidad
     * guardada), leyendo el código del argumento que recibió la plantilla —
     * es la única forma de conocerlo sin romper el encapsulamiento.
     */
    private record Emitido(String code, EmailCode entity) {}

    private Emitido emitir(User u) {
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<EmailCode> saved = ArgumentCaptor.forClass(EmailCode.class);
        service.issueAndSend(u, EmailCode.Purpose.LOGIN, DEST, "1.1.1.1");
        verify(templates).loginCode(anyString(), code.capture(), anyLong());
        verify(codeRepository).save(saved.capture());
        return new Emitido(code.getValue(), saved.getValue());
    }

    // ─── Emisión ────────────────────────────────────────────────────────────

    @Test
    void elCodigoTieneOchoDigitos() {
        // 10^8 y no 10^6: el código se copia del correo, así que los dos
        // dígitos extra no cuestan usabilidad y multiplican por 100 el trabajo
        // de quien intente adivinarlo.
        assertThat(emitir(user()).code()).matches("\\d{8}");
    }

    @Test
    void elCodigoNoSeGuardaEnClaro() {
        Emitido e = emitir(user());

        // Si la base se filtra, los códigos vivos no deben servir.
        assertThat(e.entity().getCodeHash()).isNotEqualTo(e.code());
        assertThat(e.entity().getCodeHash()).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void emitirQuemaLosCodigosAnteriores() {
        emitir(user());
        // Varios códigos vivos a la vez multiplicarían por N la probabilidad
        // de acertar uno al azar.
        verify(codeRepository).invalidateLive(eq(7), eq("login"), any());
    }

    @Test
    void sinEnvioConfiguradoNoSeEmiteNada() {
        when(mailService.isEnabled()).thenReturn(false);

        assertThatThrownBy(() -> service.issueAndSend(user(), EmailCode.Purpose.LOGIN, DEST, "1.1.1.1"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        verify(codeRepository, never()).save(any());
    }

    @Test
    void siElCorreoNoSaleElCodigoSeQuema() {
        when(mailService.send(anyString(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.issueAndSend(user(), EmailCode.Purpose.LOGIN, DEST, "1.1.1.1"))
                .isInstanceOf(ApiException.class);

        // Dos veces: la de antes de guardar y la de limpieza tras el fallo.
        // Dejarlo vivo gastaría cupo del techo horario por un código ilegible.
        verify(codeRepository, org.mockito.Mockito.times(2))
                .invalidateLive(eq(7), eq("login"), any());
    }

    @Test
    void hayCooldownEntreReenvios() {
        when(codeRepository.lastIssuedAt(7, "login"))
                .thenReturn(Optional.of(LocalDateTime.now().minusSeconds(10)));

        assertThatThrownBy(() -> service.issueAndSend(user(), EmailCode.Purpose.LOGIN, DEST, "1.1.1.1"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getStatus())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void hayTechoDeCodigosPorVentana() {
        // Sin este techo, el límite de 5 intentos por código se elude pidiendo
        // códigos nuevos sin parar.
        when(codeRepository.countIssuedSince(eq(7), eq("login"), any())).thenReturn(3L);

        assertThatThrownBy(() -> service.issueAndSend(user(), EmailCode.Purpose.LOGIN, DEST, "1.1.1.1"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getStatus())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    // ─── Verificación ───────────────────────────────────────────────────────

    @Test
    void elCodigoCorrectoSeConsumeYDevuelveElDestino() {
        User u = user();
        Emitido e = emitir(u);
        when(codeRepository.findLive(eq(7), eq("login"), any())).thenReturn(Optional.of(e.entity()));
        when(codeRepository.consume(any(), any())).thenReturn(1);

        String destino = service.verifyAndConsume(u, EmailCode.Purpose.LOGIN, e.code());

        assertThat(destino).isEqualTo(DEST);
        verify(codeRepository).consume(any(), any());
    }

    @Test
    void codigoIncorrectoSumaIntentoEnElCodigoYFalloEnLaCuenta() {
        User u = user();
        Emitido e = emitir(u);
        when(codeRepository.findLive(eq(7), eq("login"), any())).thenReturn(Optional.of(e.entity()));

        assertThatThrownBy(() -> service.verifyAndConsume(u, EmailCode.Purpose.LOGIN, "00000000"))
                .isInstanceOf(ApiException.class);

        verify(codeRepository).reserveAttempt(eq(e.entity().getId()), org.mockito.ArgumentMatchers.anyShort());
        // El contador de la cuenta se incrementa EN LA BASE. Si se hiciera en
        // memoria ("leer, sumar uno, guardar"), cinco fallos simultáneos
        // escribirían todos el mismo 1 y el bloqueo no llegaría nunca.
        verify(userRepository).incrementEmailCodeFailures(7);
        assertThat(u.getFailedEmailCodeAttempts()).isEqualTo(1);
    }

    @Test
    void elIntentoSeReservaAntesDeMirarElCodigo() {
        User u = user();
        Emitido e = emitir(u);
        when(codeRepository.findLive(eq(7), eq("login"), any())).thenReturn(Optional.of(e.entity()));
        // La base dice que ya no queda cupo para este código.
        when(codeRepository.reserveAttempt(any(), org.mockito.ArgumentMatchers.anyShort())).thenReturn(0);

        // Incluso con el código CORRECTO se rechaza: el cupo se agotó y la
        // decisión la tomó el UPDATE condicional, no un if sobre un valor leído
        // antes. Es lo que impide que N requests paralelas se cuelen todas
        // juntas por debajo del techo de 5 intentos.
        assertThatThrownBy(() -> service.verifyAndConsume(u, EmailCode.Purpose.LOGIN, e.code()))
                .isInstanceOf(ApiException.class);

        verify(codeRepository).consume(any(), any()); // y además queda quemado
    }

    @Test
    void alAgotarLosIntentosElCodigoSeQuema() {
        User u = user();
        Emitido e = emitir(u);
        when(codeRepository.findLive(eq(7), eq("login"), any())).thenReturn(Optional.of(e.entity()));

        assertThatThrownBy(() -> service.verifyAndConsume(u, EmailCode.Purpose.LOGIN, "00000000"))
                .isInstanceOf(ApiException.class);

        // El quemado también lo arbitra la base (solo si attempts >= max), así
        // que no depende de un contador en memoria que puede venir desactualizado.
        verify(codeRepository).burnIfExhausted(eq(e.entity().getId()),
                org.mockito.ArgumentMatchers.anyShort(), any());
    }

    @Test
    void elBloqueoDeCuentaLoAplicaLaBaseAlLlegarAlUmbral() {
        User u = user();
        u.setFailedEmailCodeAttempts(4);
        Emitido e = emitir(u);
        when(codeRepository.findLive(eq(7), eq("login"), any())).thenReturn(Optional.of(e.entity()));
        // La base confirma que el contador real ya cruzó el umbral.
        when(userRepository.lockEmailCodesIfExhausted(eq(7), eq(5), any())).thenReturn(1);

        assertThatThrownBy(() -> service.verifyAndConsume(u, EmailCode.Purpose.LOGIN, "00000000"))
                .isInstanceOf(ApiException.class);

        // Quién cruzó el umbral lo decide el UPDATE condicional mirando el
        // valor real, no una cuenta hecha sobre una lectura que pudo quedar
        // vieja entre peticiones concurrentes.
        verify(userRepository).lockEmailCodesIfExhausted(eq(7), eq(5), any());
        assertThat(u.getEmailCodeLockedUntil()).isAfter(LocalDateTime.now().plusMinutes(14));
    }

    @Test
    void verificarElCorreoDelPerfilNoBloqueaElInicioDeSesion() {
        User u = user();
        when(codeRepository.findLive(eq(7), eq("verify_email"), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifyAndConsume(u, EmailCode.Purpose.VERIFY_EMAIL, "00000000"))
                .isInstanceOf(ApiException.class);

        // Compartir el contador significaba que tipear mal el código de alta
        // cinco veces te dejaba sin poder entrar 15 minutos. Ahí el techo real
        // ya lo dan el límite por código y el de emisión, así que el bloqueo
        // solo agregaba un auto-DoS.
        verify(userRepository, never()).incrementEmailCodeFailures(any());
        assertThat(u.getEmailCodeLockedUntil()).isNull();
    }

    @Test
    void cuentaBloqueadaNiSiquieraMiraElCodigo() {
        User u = user();
        u.setEmailCodeLockedUntil(LocalDateTime.now().plusMinutes(10));

        assertThatThrownBy(() -> service.verifyAndConsume(u, EmailCode.Purpose.LOGIN, "12345678"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);

        // Cortocircuito real: durante el bloqueo el endpoint no sirve ni
        // siquiera como oráculo sobre si hay un código vivo.
        verify(codeRepository, never()).findLive(any(), anyString(), any());
    }

    @Test
    void elBloqueoSeVenceSoloYDejaVerificar() {
        User u = user();
        u.setFailedEmailCodeAttempts(5);
        u.setEmailCodeLockedUntil(LocalDateTime.now().minusSeconds(1));
        Emitido e = emitir(u);
        when(codeRepository.findLive(eq(7), eq("login"), any())).thenReturn(Optional.of(e.entity()));
        when(codeRepository.consume(any(), any())).thenReturn(1);

        service.verifyAndConsume(u, EmailCode.Purpose.LOGIN, e.code());

        // Un bloqueo permanente activable por un tercero sería en sí un vector
        // de denegación de servicio.
        assertThat(u.getFailedEmailCodeAttempts()).isZero();
        assertThat(u.getEmailCodeLockedUntil()).isNull();
    }

    @Test
    void sinCodigoVivoElMensajeEsElMismoQueConCodigoMalo() {
        User u = user();
        when(codeRepository.findLive(eq(7), eq("login"), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifyAndConsume(u, EmailCode.Purpose.LOGIN, "12345678"))
                .isInstanceOf(ApiException.class)
                // Distinguir "no hay código" de "código incorrecto" convertiría
                // el endpoint en un oráculo sobre el estado de la cuenta.
                .hasMessageContaining("incorrecto o vencido");
    }

    @Test
    void elConsumoPerdidoPorCarreraSeTrataComoCodigoInvalido() {
        User u = user();
        Emitido e = emitir(u);
        when(codeRepository.findLive(eq(7), eq("login"), any())).thenReturn(Optional.of(e.entity()));
        // Otra request se llevó la fila primero: el UPDATE condicional afecta 0.
        when(codeRepository.consume(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.verifyAndConsume(u, EmailCode.Purpose.LOGIN, e.code()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void unCodigoDeOtroPropositoNoSirveParaIniciarSesion() {
        User u = user();
        Emitido e = emitir(u);
        // Misma fila, pero buscada como código de verificación de correo: el
        // HMAC incluye el propósito, así que no valida.
        when(codeRepository.findLive(eq(7), eq("verify_email"), any()))
                .thenReturn(Optional.of(e.entity()));

        assertThatThrownBy(() -> service.verifyAndConsume(u, EmailCode.Purpose.VERIFY_EMAIL, e.code()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void unaEntradaQueNoEsUnCodigoTambienCuentaComoFallo() {
        User u = user();

        assertThatThrownBy(() -> service.verifyAndConsume(u, EmailCode.Purpose.LOGIN, "abc"))
                .isInstanceOf(ApiException.class);

        // Si probar basura fuera gratis, se podría sondear el estado del
        // bloqueo sin gastar intentos reales.
        assertThat(u.getFailedEmailCodeAttempts()).isEqualTo(1);
    }
}
