package com.biblioteca.service;

import com.biblioteca.exception.ApiException;
import com.biblioteca.model.EmailCode;
import com.biblioteca.model.User;
import com.biblioteca.repository.EmailCodeRepository;
import com.biblioteca.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Emisión y verificación de los códigos de un solo uso que viajan por correo.
 *
 * ===========================================================================
 * DEFENSAS CONTRA FUERZA BRUTA — el punto entero de esta clase
 * ===========================================================================
 *
 * Un código numérico enviado por correo es, por diseño, un secreto corto: si
 * el único techo fueran los intentos por IP, el espacio se recorre. Las capas,
 * de la más importante a la menos:
 *
 *  1. El código NUNCA es un factor único. Solo se emite y se verifica dentro
 *     de un flujo que ya validó la contraseña (el step token de 2FA) o que ya
 *     tiene sesión (verificación del correo del perfil). Quien no sabe la
 *     contraseña no llega a probar ni un código.
 *
 *  2. OCHO dígitos, no seis: 10^8 en vez de 10^6. El código se copia del
 *     correo, no se memoriza, así que los dos dígitos extra no cuestan nada de
 *     usabilidad y multiplican por 100 el trabajo del atacante.
 *
 *  3. Un solo código vivo por (usuario, propósito). Emitir uno nuevo quema los
 *     anteriores: si no, N códigos simultáneos multiplican por N la
 *     probabilidad de acertar uno al azar.
 *
 *  4. Cinco intentos POR CÓDIGO. Al sexto el código se quema y hay que pedir
 *     otro — que a su vez pasa por el techo de emisión del punto 6.
 *
 *  5. Bloqueo POR CUENTA (5 fallos consecutivos → 15 min), en columnas propias
 *     de users y aplicado con UPDATEs condicionales para que no se pueda
 *     esquivar con peticiones en paralelo. Es el techo que el rate-limit por
 *     IP no puede dar: una botnet lo evade por definición. Mismo razonamiento
 *     que motivó V6 y V7. Solo aplica al código de INICIO DE SESIÓN — ver
 *     {@link #locksAccount}.
 *
 *  6. Techo de EMISIÓN: 60 s de cooldown, 3 códigos por 15 min y 10 por hora.
 *     Sin esto, el punto 4 se elude pidiendo códigos nuevos sin parar. De paso
 *     evita convertir el endpoint en un cañón de correo hacia el buzón ajeno.
 *
 *  7. El código se guarda como HMAC-SHA256 con clave de aplicación y se
 *     compara en tiempo constante. Una lectura de la base no entrega códigos
 *     vivos, y el tiempo de respuesta no filtra cuántos dígitos acertaste.
 *
 * Todo lo que devuelve un error de verificación usa EL MISMO mensaje: código
 * incorrecto, vencido, ya usado o inexistente son indistinguibles desde
 * afuera. Distinguirlos convertiría al endpoint en un oráculo sobre el estado
 * interno de la cuenta.
 */
@Service
public class EmailCodeService {

    private static final Logger log = LoggerFactory.getLogger(EmailCodeService.class);

    /** 8 dígitos → 10^8 combinaciones. Ver punto 2 arriba. */
    private static final int CODE_DIGITS = 8;
    private static final int CODE_BOUND = 100_000_000;

    public static final Duration TTL = Duration.ofMinutes(10);

    private static final short MAX_ATTEMPTS_PER_CODE = 5;
    private static final int MAX_ACCOUNT_FAILURES = 5;
    private static final Duration ACCOUNT_LOCKOUT = Duration.ofMinutes(15);

    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    private static final int MAX_PER_15_MIN = 3;
    private static final int MAX_PER_HOUR = 10;

    /**
     * Un único mensaje para todos los modos de fallo de la verificación. Ver
     * el comentario de clase: separarlos filtra estado.
     */
    private static final String GENERIC_FAILURE = "Código incorrecto o vencido. Pedí uno nuevo.";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final EmailCodeRepository codeRepository;
    private final UserRepository userRepository;
    private final MailService mailService;
    private final EmailTemplates templates;
    private final SecretKeySpec hmacKey;

    public EmailCodeService(EmailCodeRepository codeRepository,
                            UserRepository userRepository,
                            MailService mailService,
                            EmailTemplates templates,
                            @Value("${app.encryption.key}") String base64Key) {
        this.codeRepository = codeRepository;
        this.userRepository = userRepository;
        this.mailService = mailService;
        this.templates = templates;
        // Se reutiliza la clave de cifrado de la aplicación. La separación de
        // dominios no la da una clave distinta sino el prefijo del mensaje
        // (ver hash()), así que un HMAC de esta clase nunca colisiona con otro
        // uso de la misma clave.
        this.hmacKey = new SecretKeySpec(Base64.getDecoder().decode(base64Key), "HmacSHA256");
    }

    /** Datos que el caller necesita para responderle al usuario. */
    public record Issued(LocalDateTime expiresAt, String maskedDestination) {}

    // ======================================================================
    // Emisión
    // ======================================================================

    /**
     * Emite un código y lo manda. Si el envío falla, el código se quema antes
     * de salir: dejarlo vivo gastaría un intento del techo horario a cambio de
     * un código que nadie va a poder leer.
     *
     * @param destination a qué dirección enviarlo — puede no ser la misma que
     *                    {@code user.getEmail()} (en el alta se manda a
     *                    {@code pendingEmail}, que todavía no es el correo de
     *                    la cuenta).
     */
    public Issued issueAndSend(User user, EmailCode.Purpose purpose, String destination, String ip) {
        if (!mailService.isEnabled()) {
            throw ApiException.unavailable(
                    "El envío de correo no está configurado. Avisá a un administrador.");
        }
        if (!EmailAddresses.isValid(destination)) {
            throw ApiException.badRequest("La dirección de correo no es válida");
        }
        enforceIssueLimits(user, purpose);

        LocalDateTime now = LocalDateTime.now();
        String code = generateCode();

        // Primero se queman los vivos, después se guarda el nuevo. En este
        // orden el peor caso de una interrupción es "el usuario se quedó sin
        // código y pide otro"; al revés, el peor caso es dos códigos vivos.
        codeRepository.invalidateLive(user.getId(), purpose.value(), now);

        EmailCode entity = new EmailCode();
        entity.setUserId(user.getId());
        entity.setPurpose(purpose.value());
        entity.setCodeHash(hash(user.getId(), purpose, code));
        entity.setDestination(destination);
        entity.setCreatedAt(now);
        entity.setExpiresAt(now.plus(TTL));
        entity.setIp(ip);
        codeRepository.save(entity);

        long minutes = TTL.toMinutes();
        MailService.Mail mail = switch (purpose) {
            case LOGIN -> templates.loginCode(displayName(user), code, minutes);
            case VERIFY_EMAIL -> templates.verifyEmailCode(displayName(user), code, minutes);
        };

        if (!mailService.send(destination, mail)) {
            codeRepository.invalidateLive(user.getId(), purpose.value(), now);
            throw ApiException.unavailable(
                    "No pudimos enviar el correo. Intentá de nuevo en un momento.");
        }

        return new Issued(entity.getExpiresAt(), EmailAddresses.mask(destination));
    }

    /**
     * Cooldown entre reenvíos y techos por ventana. Se cuenta sobre la tabla,
     * no sobre un contador en memoria ni en Redis: así el techo sobrevive a un
     * reinicio de la aplicación y no depende de un servicio que puede estar
     * caído (el limitador por IP es fail-open justamente por eso).
     */
    private void enforceIssueLimits(User user, EmailCode.Purpose purpose) {
        LocalDateTime now = LocalDateTime.now();

        Optional<LocalDateTime> last = codeRepository.lastIssuedAt(user.getId(), purpose.value());
        if (last.isPresent()) {
            long secondsSince = Duration.between(last.get(), now).getSeconds();
            if (secondsSince < RESEND_COOLDOWN.getSeconds()) {
                long wait = RESEND_COOLDOWN.getSeconds() - secondsSince;
                throw ApiException.tooManyRequests(
                        "Esperá " + wait + " segundos antes de pedir otro código.");
            }
        }

        if (codeRepository.countIssuedSince(user.getId(), purpose.value(),
                now.minusMinutes(15)) >= MAX_PER_15_MIN) {
            throw ApiException.tooManyRequests(
                    "Pediste demasiados códigos. Esperá unos minutos antes de intentar de nuevo.");
        }
        if (codeRepository.countIssuedSince(user.getId(), purpose.value(),
                now.minusHours(1)) >= MAX_PER_HOUR) {
            throw ApiException.tooManyRequests(
                    "Pediste demasiados códigos en la última hora. Intentá más tarde.");
        }
    }

    // ======================================================================
    // Verificación
    // ======================================================================

    /**
     * Verifica y consume el código. Devuelve la dirección a la que se había
     * enviado — el alta de correo la necesita para saber qué dirección quedó
     * probada, y no puede confiar en {@code pendingEmail} porque pudo cambiar
     * entre la emisión y la confirmación.
     *
     * Lanza siempre {@link #GENERIC_FAILURE} ante cualquier fallo, salvo el
     * bloqueo por cuenta, que sí se informa: ahí el usuario legítimo necesita
     * saber que tiene que esperar en vez de seguir probando.
     */
    public String verifyAndConsume(User user, EmailCode.Purpose purpose, String code) {
        enforceAccountLockout(user, purpose);

        if (code == null || !code.matches("\\d{" + CODE_DIGITS + "}")) {
            // Se cuenta como fallo igual: si no, probar basura sería gratis y
            // el atacante podría sondear el estado del lockout sin gastar
            // intentos reales.
            registerFailure(user, purpose);
            throw ApiException.unauthorized(GENERIC_FAILURE);
        }

        LocalDateTime now = LocalDateTime.now();
        Optional<EmailCode> found = codeRepository.findLive(user.getId(), purpose.value(), now);
        if (found.isEmpty()) {
            registerFailure(user, purpose);
            throw ApiException.unauthorized(GENERIC_FAILURE);
        }
        EmailCode live = found.get();

        // Se reserva el intento ANTES de mirar el código. Es la base la que
        // decide si quedaba cupo (UPDATE condicional), no un if sobre un valor
        // leído antes: con "leer → decidir → incrementar", N requests paralelas
        // leen todas el mismo contador y todas se creen dentro del límite, así
        // que el techo de 5 intentos se evapora contra quien paraleliza.
        //
        // Que se reserve antes de comparar también cierra el caso de la request
        // abortada a mitad: el intento ya quedó contado.
        if (codeRepository.reserveAttempt(live.getId(), MAX_ATTEMPTS_PER_CODE) == 0) {
            codeRepository.consume(live.getId(), now);
            registerFailure(user, purpose);
            throw ApiException.unauthorized(GENERIC_FAILURE);
        }

        String expected = live.getCodeHash();
        String actual = hash(user.getId(), purpose, code);
        // Comparación en tiempo constante: un equals() corriente termina en el
        // primer byte distinto y el tiempo de respuesta filtra cuánto prefijo
        // acertó quien probó.
        boolean ok = MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));

        if (!ok) {
            // Si ese era el último cupo, el código queda quemado acá mismo y el
            // siguiente intento ni siquiera lo encuentra vivo.
            codeRepository.burnIfExhausted(live.getId(), MAX_ATTEMPTS_PER_CODE, now);
            registerFailure(user, purpose);
            throw ApiException.unauthorized(GENERIC_FAILURE);
        }

        // Consumo atómico: si dos requests llegan a la vez con el mismo código
        // válido, solo uno recibe 1 acá y el otro lo trata como inválido.
        if (codeRepository.consume(live.getId(), now) == 0) {
            registerFailure(user, purpose);
            throw ApiException.unauthorized(GENERIC_FAILURE);
        }

        clearFailures(user, purpose);
        return live.getDestination();
    }

    /** Quema los códigos vivos de un propósito — para cancelar un alta a medias. */
    public void invalidate(User user, EmailCode.Purpose purpose) {
        codeRepository.invalidateLive(user.getId(), purpose.value(), LocalDateTime.now());
    }

    // ======================================================================
    // Bloqueo por cuenta
    // ======================================================================

    /**
     * El bloqueo por cuenta se aplica SOLO al código de inicio de sesión.
     *
     * En la verificación del correo del perfil no hace falta y molesta: para
     * llegar ahí ya hay que tener sesión y haber puesto la contraseña, y el
     * techo real lo dan el límite por código (5) y el de emisión (3 cada 15
     * min) — 15 intentos por cuarto de hora contra 10^8. Compartir el contador
     * significaba que tipear mal el código de alta cinco veces te dejaba sin
     * poder iniciar sesión durante 15 minutos: un auto-bloqueo que no compra
     * ninguna seguridad.
     */
    private static boolean locksAccount(EmailCode.Purpose purpose) {
        return purpose == EmailCode.Purpose.LOGIN;
    }

    private void enforceAccountLockout(User user, EmailCode.Purpose purpose) {
        if (!locksAccount(purpose)) return;
        LocalDateTime until = user.getEmailCodeLockedUntil();
        if (until != null && until.isAfter(LocalDateTime.now())) {
            throw ApiException.forbidden(
                    "Demasiados códigos incorrectos. Intentá de nuevo en unos minutos.");
        }
    }

    /**
     * Suma el fallo EN LA BASE, no en memoria.
     *
     * Con "leer el contador de la entidad, sumarle uno y guardar", N intentos
     * simultáneos leen todos el mismo valor y guardan todos el mismo
     * resultado: cinco fallos paralelos cuentan como uno y el bloqueo no
     * llega nunca. Justo el escenario que este bloqueo existe para cubrir —
     * el atacante distribuido que el límite por IP no alcanza.
     *
     * La entidad en memoria se actualiza también, pero solo para que el resto
     * de la petición vea algo coherente: la fuente de verdad es la base.
     */
    private void registerFailure(User user, EmailCode.Purpose purpose) {
        if (!locksAccount(purpose)) return;
        userRepository.incrementEmailCodeFailures(user.getId());
        user.setFailedEmailCodeAttempts(user.getFailedEmailCodeAttempts() + 1);

        LocalDateTime until = LocalDateTime.now().plus(ACCOUNT_LOCKOUT);
        if (userRepository.lockEmailCodesIfExhausted(
                user.getId(), MAX_ACCOUNT_FAILURES, until) > 0) {
            user.setEmailCodeLockedUntil(until);
            log.warn("Bloqueo por códigos de correo activado para userId={}", user.getId());
        }
    }

    private void clearFailures(User user, EmailCode.Purpose purpose) {
        if (!locksAccount(purpose)) return;
        if (user.getFailedEmailCodeAttempts() != 0 || user.getEmailCodeLockedUntil() != null) {
            userRepository.clearEmailCodeFailures(user.getId());
            user.setFailedEmailCodeAttempts(0);
            user.setEmailCodeLockedUntil(null);
        }
    }

    // ======================================================================
    // Utilidades
    // ======================================================================

    /**
     * Uniforme sobre [0, 10^8). Se usa nextInt(bound) y no un módulo sobre
     * bytes crudos: el módulo sesga las primeras combinaciones cuando el rango
     * no divide exacto, y un generador de códigos sesgado es un generador con
     * menos entropía real que la que aparenta.
     */
    private static String generateCode() {
        return String.format("%0" + CODE_DIGITS + "d", RANDOM.nextInt(CODE_BOUND));
    }

    /**
     * HMAC-SHA256 en hex. El mensaje incluye propósito y usuario, así que el
     * hash queda atado a los dos: una fila movida de un usuario a otro, o de
     * "verificar correo" a "iniciar sesión", deja de validar.
     */
    private String hash(Integer userId, EmailCode.Purpose purpose, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            String message = "email-code:" + purpose.value() + ":" + userId + ":" + code;
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo calcular el HMAC del código", e);
        }
    }

    private static String displayName(User user) {
        String full = user.getFullName();
        return full != null && !full.isBlank() ? full : user.getUsername();
    }

    /**
     * Borra los códigos vencidos hace más de un día. Sin esto la tabla crece
     * sin techo: cada inicio de sesión con 2FA por correo deja una fila que
     * pierde toda utilidad a los diez minutos.
     */
    @Scheduled(cron = "0 30 3 * * *")
    public void purgeExpired() {
        try {
            int deleted = codeRepository.deleteExpiredBefore(LocalDateTime.now().minusDays(1));
            if (deleted > 0) {
                log.info("Limpieza de email_codes: {} filas vencidas eliminadas", deleted);
            }
        } catch (Exception e) {
            log.error("Falló la limpieza de email_codes: {}", e.getMessage());
        }
    }
}
