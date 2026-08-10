package com.biblioteca.service;

import com.biblioteca.dto.*;
import com.biblioteca.exception.ApiException;
import com.biblioteca.model.EmailCode;
import com.biblioteca.model.User;
import com.biblioteca.repository.UserRepository;
import com.biblioteca.security.EncryptionService;
import com.biblioteca.security.JwtTokenProvider;
import com.biblioteca.security.Permissions;
import com.biblioteca.security.AccessTokenDenylistService;
import com.biblioteca.security.RefreshTokenService;
import com.biblioteca.security.TotpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final TotpService totpService;
    private final RefreshTokenService refreshTokenService;
    private final EncryptionService encryptionService;
    private final AccessTokenDenylistService accessTokenDenylist;
    private final EmailCodeService emailCodeService;
    private final LoginAlertService loginAlertService;
    private final MailService mailService;
    private final EmailTemplates emailTemplates;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       JwtTokenProvider tokenProvider, TotpService totpService,
                       RefreshTokenService refreshTokenService,
                       EncryptionService encryptionService,
                       AccessTokenDenylistService accessTokenDenylist,
                       EmailCodeService emailCodeService,
                       LoginAlertService loginAlertService,
                       MailService mailService,
                       EmailTemplates emailTemplates) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.totpService = totpService;
        this.refreshTokenService = refreshTokenService;
        this.encryptionService = encryptionService;
        this.accessTokenDenylist = accessTokenDenylist;
        this.emailCodeService = emailCodeService;
        this.loginAlertService = loginAlertService;
        this.mailService = mailService;
        this.emailTemplates = emailTemplates;
        this.dummyHash = passwordEncoder.encode(java.util.UUID.randomUUID().toString());
    }

    /**
     * Datos de la request que necesitan el registro de sesión y los avisos.
     * Van juntos en un record para no arrastrar tres parámetros sueltos por
     * toda la cadena de llamadas.
     *
     * @param deviceCookie valor de la cookie de dispositivo que trajo el
     *                     navegador, o null si es la primera vez.
     */
    public record LoginContext(String ip, String userAgent, String deviceCookie) {}

    /**
     * Result of a successful authentication. Carries the access token (for the
     * response body) and the raw refresh token (which the controller must put
     * into an httpOnly cookie). For 2FA-pending logins, refreshToken is null
     * and only the step token in {@link LoginResponse} is populated.
     *
     * @param deviceCookie valor a reemitir en la cookie de dispositivo, o null
     *                     si esta respuesta no abre sesión (2FA pendiente,
     *                     refresh) y por lo tanto no toca el registro.
     */
    public record AuthResult(LoginResponse body, String refreshToken, String deviceCookie) {}

    /**
     * Bloqueo por cuenta para /login. El rate-limit de RateLimitFilter es por IP
     * (8/min), así que un ataque distribuido (botnet, proxies rotativos) lo evade
     * por completo: sin contador ligado al usuario no había ningún techo real de
     * intentos por cuenta.
     *
     * Umbral 10 (más laxo que los 5 de /change-password) porque el login lo usan
     * personas que tipean mal la contraseña a diario, y porque un lockout activable
     * por terceros es en sí un vector de DoS — 10 fallos consecutivos ya no es un
     * error honesto, y los 15 min se vencen solos.
     *
     * Comparte columnas con el lockout de /change-password (V6). Al compartirlas,
     * la operación más estricta gana: alguien con 6 fallos de login entra bloqueado
     * a /change-password. Es la dirección segura del error, así que se deja así.
     */
    private static final int MAX_LOGIN_ATTEMPTS = 10;
    private static final java.time.Duration LOGIN_LOCKOUT = java.time.Duration.ofMinutes(15);

    /**
     * Bloqueo por cuenta para la verificación del código TOTP.
     *
     * El rate-limit de RateLimitFilter es por IP, y por IP no hay techo real:
     * una botnet lo evade por definición, y una sola máquina también si logra
     * influir en la IP que ve la aplicación. Sin un contador ligado a la cuenta,
     * el espacio de 10^6 códigos (×3 simultáneos por WINDOW=1) quedaba abierto
     * para quien ya tuviera la contraseña — es decir, el segundo factor no
     * agregaba nada frente al escenario para el que existe.
     *
     * Umbral 5 y no 10 como el login: acá no hay margen para el error honesto
     * repetido (el código se copia de una app, no se recuerda de memoria), y un
     * segundo factor forzable no sirve de nada. Los 15 minutos se vencen solos,
     * así que un tercero no puede dejar la cuenta bloqueada de forma permanente.
     */
    private static final int MAX_TOTP_ATTEMPTS = 5;
    private static final java.time.Duration TOTP_LOCKOUT = java.time.Duration.ofMinutes(15);

    /**
     * Hash BCrypt de una contraseña aleatoria, generado una vez al arranque.
     * Sirve para gastar el mismo tiempo de CPU cuando el usuario NO existe: antes
     * el 401 salía de inmediato y el de contraseña mala tardaba ~200 ms (BCrypt
     * cost 12), lo que convertía al login en un oráculo de enumeración de usuarios
     * medible desde afuera.
     *
     * Nota: los hashes heredados de Werkzeug (pbkdf2) tienen un perfil de tiempo
     * distinto al de BCrypt, así que la equiparación es aproximada, no perfecta.
     * Cierra la diferencia grande (hashear vs. no hashear), que es la explotable.
     */
    private final String dummyHash;

    public AuthResult login(LoginRequest request, LoginContext ctx) {
        Optional<User> found = userRepository.findByUsername(request.getUsername());
        if (found.isEmpty()) {
            passwordEncoder.matches(request.getPassword(), dummyHash); // igualar tiempos
            throw ApiException.unauthorized("Credenciales incorrectas");
        }
        User user = found.get();

        LocalDateTime lockedUntil = user.getPasswordLockedUntil();
        if (lockedUntil != null && lockedUntil.isAfter(LocalDateTime.now())) {
            throw ApiException.forbidden(
                    "Demasiados intentos fallidos. Intenta de nuevo en unos minutos.");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            registerPasswordFailure(user, MAX_LOGIN_ATTEMPTS, LOGIN_LOCKOUT);
            throw ApiException.unauthorized("Credenciales incorrectas");
        }

        // Contraseña correcta → se limpia el estado de fallos. Se hace ANTES de la
        // rama de 2FA a propósito: quien probó la contraseña ya demostró conocerla,
        // y dejar el contador cargado bloquearía su próximo login legítimo.
        clearPasswordFailures(user);

        boolean totpEnabled = hasTotp(user);
        boolean emailEnabled = hasEmailFactor(user);

        if (totpEnabled || emailEnabled) {
            String stepToken = tokenProvider.generate2faStepToken(
                    user.getId(), user.getUsername(), request.isRemember());

            List<String> methods = new ArrayList<>();
            if (totpEnabled) methods.add(METHOD_TOTP);
            if (emailEnabled) methods.add(METHOD_EMAIL);

            LoginResponse body = LoginResponse.twoFactorPending(stepToken, "Se requiere código 2FA");
            body.setMethods(methods);
            body.setMaskedEmail(emailEnabled ? EmailAddresses.mask(user.getEmail()) : null);

            // Si el correo es el ÚNICO segundo factor, el código se manda acá
            // mismo: obligar al usuario a pulsar "enviame el código" cuando no
            // hay nada más que elegir es un paso sin sentido.
            //
            // Cuando TOTP también está activo NO se manda: la mayoría va a usar
            // la app, y disparar un correo en cada login sería ruido — el envío
            // queda a un clic de distancia en la pantalla de verificación.
            if (emailEnabled && !totpEnabled) {
                try {
                    emailCodeService.issueAndSend(user, EmailCode.Purpose.LOGIN,
                            user.getEmail(), ctx.ip());
                    body.setCodeSent(true);
                } catch (Exception e) {
                    // Se atrapa Exception y no solo ApiException: además del
                    // cooldown o del proveedor caído, acá adentro hay escrituras
                    // a la base, y un problema de base convertiría en 500 un
                    // login cuya contraseña ya se validó. El paso 1 tiene que
                    // sobrevivir a que el envío falle por el motivo que sea —
                    // la pantalla de verificación ofrece reenviar, y ahí sí el
                    // usuario ve el motivo real.
                    log.warn("No se envió el código de login a userId={}: {}",
                            user.getId(), e.toString());
                }
            }
            return new AuthResult(body, null, null);
        }

        return issueSession(user, request.isRemember(), ctx);
    }

    /** Nombres de los métodos de segundo factor, tal como los ve el frontend. */
    public static final String METHOD_TOTP = "totp";
    public static final String METHOD_EMAIL = "email";

    private static boolean hasTotp(User user) {
        return user.getTotpSecret() != null && !user.getTotpSecret().isEmpty();
    }

    /**
     * El factor por correo cuenta solo si está activado, el correo está
     * verificado Y el rol lo admite. Lo último se comprueba también acá, en el
     * camino del login, y no solo al activarlo: si una cuenta es promovida a
     * super_admin después de haberlo encendido, el factor tiene que dejar de
     * valer en ese mismo momento, sin depender de que alguien se acuerde de
     * apagárselo.
     */
    private static boolean hasEmailFactor(User user) {
        return user.isEmail2faEnabled()
                && user.hasUsableEmail()
                && Permissions.canUseEmailAsSecondFactor(user.getRole(), user.getUsername());
    }

    public AuthResult verify2fa(Verify2faRequest request, LoginContext ctx) {
        User user = userFromStepToken(request.getStepToken());

        // Se comprueba ANTES de mirar el secret: mientras dura el bloqueo no se
        // verifica ningún código, así que el endpoint tampoco sirve de oráculo.
        enforceTotpLockout(user);

        if (!hasTotp(user)) {
            throw ApiException.badRequest("2FA no está configurado para este usuario");
        }

        if (!totpService.verify(encryptionService.decrypt(user.getTotpSecret()), request.getCode())) {
            registerTotpFailure(user);
            throw ApiException.unauthorized("Código incorrecto");
        }
        clearTotpFailures(user);

        // "Remember" was decided at step 1 and is carried in the step token,
        // so it can't be flipped between login and 2FA.
        boolean remember = tokenProvider.getRememberFromToken(request.getStepToken());
        return issueSession(user, remember, ctx);
    }

    /**
     * Envía (o reenvía) el código de acceso durante un login pendiente de 2FA.
     *
     * El destinatario sale del step token, nunca del cuerpo del request: es lo
     * que impide que este endpoint se use para mandarle correos a la cuenta de
     * cualquiera con solo saber un nombre de usuario.
     */
    public EmailCodeService.Issued requestLoginCode(String stepToken, String ip) {
        User user = userFromStepToken(stepToken);
        if (!hasEmailFactor(user)) {
            throw ApiException.badRequest(
                    "Esta cuenta no tiene el código por correo activado");
        }
        return emailCodeService.issueAndSend(user, EmailCode.Purpose.LOGIN, user.getEmail(), ip);
    }

    /** Segundo paso del login cuando el factor elegido es el código por correo. */
    public AuthResult verifyEmailCode(VerifyEmailCodeRequest request, LoginContext ctx) {
        User user = userFromStepToken(request.getStepToken());
        if (!hasEmailFactor(user)) {
            throw ApiException.badRequest(
                    "Esta cuenta no tiene el código por correo activado");
        }
        emailCodeService.verifyAndConsume(user, EmailCode.Purpose.LOGIN, request.getCode());

        boolean remember = tokenProvider.getRememberFromToken(request.getStepToken());
        return issueSession(user, remember, ctx);
    }

    /**
     * Valida el step token y devuelve el usuario al que pertenece.
     *
     * El chequeo de scope no es decorativo: sin él, un access token corriente
     * serviría para completar el segundo factor de su propio dueño, y el paso
     * dejaría de probar nada.
     */
    private User userFromStepToken(String stepToken) {
        if (stepToken == null || !tokenProvider.validateToken(stepToken)) {
            throw ApiException.unauthorized("Sesión 2FA expirada, vuelve a iniciar sesión");
        }
        if (!"2fa-pending".equals(tokenProvider.getScopeFromToken(stepToken))) {
            throw ApiException.unauthorized("Token inválido para 2FA");
        }
        Integer userId = tokenProvider.getUserIdFromToken(stepToken);
        return userRepository.findById(userId)
                .orElseThrow(() -> ApiException.unauthorized("Sesión 2FA inválida"));
    }

    /** Rechaza la operación si la cuenta está bloqueada por fallos de TOTP. */
    private void enforceTotpLockout(User user) {
        LocalDateTime lockedUntil = user.getTotpLockedUntil();
        if (lockedUntil != null && lockedUntil.isAfter(LocalDateTime.now())) {
            throw ApiException.forbidden(
                    "Demasiados códigos incorrectos. Intenta de nuevo en unos minutos.");
        }
    }

    /**
     * Suma un fallo de TOTP y bloquea al llegar al umbral.
     *
     * El incremento y el bloqueo son dos UPDATEs condicionales contra la base,
     * no un cálculo sobre la entidad en memoria. Con lo segundo, N intentos
     * simultáneos leen todos el mismo contador y guardan todos el mismo valor:
     * el bloqueo no llega nunca justo contra el atacante que paraleliza, que
     * es el único escenario donde este techo importa (para el resto ya está el
     * límite por IP). Ver el bloque de comentarios de UserRepository.
     */
    private void registerTotpFailure(User user) {
        userRepository.incrementTotpFailures(user.getId());
        user.setFailedTotpAttempts(user.getFailedTotpAttempts() + 1);

        LocalDateTime until = LocalDateTime.now().plus(TOTP_LOCKOUT);
        if (userRepository.lockTotpIfExhausted(user.getId(), MAX_TOTP_ATTEMPTS, until) > 0) {
            user.setTotpLockedUntil(until);
            // El 2FA es la última línea de defensa de la cuenta: que se agote
            // merece quedar en el log aunque el bloqueo se venza solo.
            log.warn("TOTP lockout activado para userId={}", user.getId());
        }
    }

    /** Limpia el estado de fallos tras un código correcto. */
    private void clearTotpFailures(User user) {
        if (user.getFailedTotpAttempts() != 0 || user.getTotpLockedUntil() != null) {
            userRepository.clearTotpFailures(user.getId());
            user.setFailedTotpAttempts(0);
            user.setTotpLockedUntil(null);
        }
    }

    /**
     * Suma un fallo de contraseña y bloquea al llegar al umbral, de forma
     * atómica. El umbral es parámetro porque las mismas columnas las comparten
     * el login (10 intentos) y la verificación de contraseña actual (5): la
     * operación más estricta gana, que es la dirección segura del error.
     */
    public void registerPasswordFailure(User user, int threshold, java.time.Duration lockout) {
        userRepository.incrementPasswordFailures(user.getId());
        user.setFailedPasswordAttempts(user.getFailedPasswordAttempts() + 1);

        LocalDateTime until = LocalDateTime.now().plus(lockout);
        if (userRepository.lockPasswordIfExhausted(user.getId(), threshold, until) > 0) {
            user.setPasswordLockedUntil(until);
            log.warn("Bloqueo por contraseña activado para userId={}", user.getId());
        }
    }

    /** Limpia el estado de fallos de contraseña tras un acierto. */
    public void clearPasswordFailures(User user) {
        if (user.getFailedPasswordAttempts() != 0 || user.getPasswordLockedUntil() != null) {
            userRepository.clearPasswordFailures(user.getId());
            user.setFailedPasswordAttempts(0);
            user.setPasswordLockedUntil(null);
        }
    }

    /**
     * Issues an access token. When {@code remember} is true, also issues a
     * refresh token so the session can survive past the 15-minute access TTL;
     * when false, no refresh is issued and the session ends when the access
     * token expires.
     */
    private AuthResult issueSession(User user, boolean remember, LoginContext ctx) {
        String token = tokenProvider.generateToken(user.getId(), user.getUsername(), user.getRole());
        String refreshToken = remember
                ? refreshTokenService.issue(user.getId(), ctx.ip(), ctx.userAgent())
                : null;

        // El aviso se dispara ACÁ y no en el controller porque éste es el único
        // punto por el que sale una sesión nueva: login directo, verify-2fa y
        // verify-email-code pasan los tres por acá. Colgarlo de cada endpoint
        // significaría que el próximo camino de autenticación que se agregue
        // nazca sin aviso y nadie lo note.
        //
        // refresh() NO pasa por acá a propósito: renovar no es iniciar sesión, y
        // avisar cada 15 minutos volvería inútil el aviso.
        LoginAlertService.DeviceCheck device = loginAlertService.registerDevice(
                user.getId(), ctx.deviceCookie(), ctx.userAgent(), ctx.ip());
        loginAlertService.notifyLogin(user, device, ctx.ip());

        return new AuthResult(new LoginResponse(token, toDto(user)), refreshToken, device.cookieValue());
    }

    /**
     * Rotates the refresh token and mints a matching access token.
     * Returns body (with new access token + user) and the new raw refresh.
     */
    public AuthResult refresh(String currentRefresh, String ip, String userAgent) {
        RefreshTokenService.Rotated rotated = refreshTokenService.rotate(currentRefresh, ip, userAgent);
        User user = userRepository.findById(rotated.userId())
                .orElseThrow(() -> ApiException.unauthorized("Sesión inválida"));
        String token = tokenProvider.generateToken(user.getId(), user.getUsername(), user.getRole());
        // deviceCookie null: renovar no cuenta como inicio de sesión, así que
        // no toca el registro de dispositivos ni reemite la cookie.
        return new AuthResult(new LoginResponse(token, toDto(user)), rotated.rawToken(), null);
    }

    /**
     * Cierra la sesión: revoca el refresh token y además invalida el access
     * token que se está usando.
     *
     * Sin lo segundo, "cerrar sesión" solo impedía renovar: el access token ya
     * emitido seguía siendo válido hasta 15 minutos más. Quien lo hubiera
     * capturado conservaba el acceso durante ese rato, que es exactamente lo
     * que el usuario cree estar cortando al pulsar el botón.
     */
    public void logout(String currentRefresh, String accessToken) {
        refreshTokenService.revoke(currentRefresh);
        accessTokenDenylist.revoke(accessToken);
    }

    /**
     * Genera un secret nuevo y lo deja guardado (cifrado) como pendiente de
     * confirmación. Se persiste en el servidor en vez de confiar en que el
     * cliente lo devuelva en /confirm-2fa: si el secret llega desde afuera,
     * el factor que termina protegiendo la cuenta lo elige quien llama.
     *
     * Sobrescribir un pendiente anterior es intencional — pedir /setup-2fa de
     * nuevo significa "descarta el QR anterior, dame otro".
     */
    public String setup2fa(User user) {
        String secret = totpService.newSecret();
        user.setTotpPendingSecret(encryptionService.encrypt(secret));
        userRepository.save(user);
        return secret;
    }

    public String otpAuthUri(String username, String secret) {
        return totpService.otpAuthUri("Maxipet", username, secret);
    }

    /**
     * Confirma el secret que /setup-2fa dejó pendiente y lo activa.
     *
     * El código se valida contra el secret guardado en el servidor, no contra
     * uno recibido del cliente. Comparte el lockout de {@link #verify2fa}: sin
     * él, este endpoint quedaba como una vía alternativa para probar códigos.
     */
    public void verifyAndEnable2fa(User user, String code) {
        String pending = user.getTotpPendingSecret();
        if (pending == null || pending.isBlank()) {
            throw ApiException.badRequest(
                    "No hay una configuración de 2FA pendiente. Vuelve a generar el código QR.");
        }
        enforceTotpLockout(user);
        if (!totpService.verify(encryptionService.decrypt(pending), code)) {
            registerTotpFailure(user);
            throw ApiException.badRequest("Código incorrecto, intenta de nuevo");
        }
        // Ya viene cifrado desde setup2fa — se promueve tal cual, sin volver a
        // cifrar (doble cifrado dejaría el secret indescifrable).
        user.setTotpSecret(pending);
        user.setTotpPendingSecret(null);
        user.setFailedTotpAttempts(0);
        user.setTotpLockedUntil(null);
        userRepository.save(user);
    }

    /**
     * Desactiva el 2FA del propio usuario.
     *
     * Exige un código vigente además de la contraseña (que valida el caller).
     * El código prueba posesión del dispositivo: sin él, un access token robado
     * bastaría para quitarle el segundo factor a la cuenta, que es justo lo que
     * el segundo factor tiene que impedir.
     *
     * Para el caso de teléfono perdido — donde el usuario legítimo tampoco
     * puede generar un código — existe {@link #clear2fa}, que solo puede
     * disparar un super_admin.
     */
    public void disable2fa(User user, String code) {
        if (user.getTotpSecret() == null || user.getTotpSecret().isEmpty()) {
            throw ApiException.badRequest("La verificación en dos pasos no está activa");
        }
        // Mismo lockout que verify2fa: acá el bucket de 5/min por IP era el
        // único techo del código, y un techo por IP no cubre a un atacante
        // que puede presentarse desde varias.
        enforceTotpLockout(user);
        if (!totpService.verify(encryptionService.decrypt(user.getTotpSecret()), code)) {
            registerTotpFailure(user);
            throw ApiException.badRequest("Código incorrecto, intenta de nuevo");
        }
        user.setTotpSecret(null);
        user.setTotpPendingSecret(null);
        user.setFailedTotpAttempts(0);
        user.setTotpLockedUntil(null);
        userRepository.save(user);
    }

    /**
     * Reseteo administrativo del 2FA: lo limpia sin pedir código, para destrabar
     * a quien perdió el dispositivo. El control de permisos y el registro en
     * bitácora corren por cuenta del caller.
     *
     * Limpia LOS DOS segundos factores, no solo el TOTP. Si dejara el código
     * por correo activo, quien perdiera el acceso a su buzón quedaría fuera de
     * su cuenta sin ninguna vía de rescate — el mismo callejón sin salida que
     * este reseteo existe para evitar, solo que por el otro factor.
     *
     * El correo NO se borra: sigue sirviendo para los avisos, que es
     * justamente lo que conviene mantener encendido después de un reseteo
     * pedido por sospecha de acceso indebido.
     */
    public void clear2fa(User user) {
        user.setTotpSecret(null);
        user.setTotpPendingSecret(null);
        // También se limpia el bloqueo: si el reseteo existe para destrabar a
        // quien perdió el dispositivo, dejarlo bloqueado lo mandaría a esperar
        // 15 minutos para configurar el factor nuevo.
        user.setFailedTotpAttempts(0);
        user.setTotpLockedUntil(null);
        user.setEmail2faEnabled(false);
        user.setFailedEmailCodeAttempts(0);
        user.setEmailCodeLockedUntil(null);
        userRepository.save(user);
        emailCodeService.invalidate(user, EmailCode.Purpose.LOGIN);
    }

    // ======================================================================
    // Correo de la cuenta
    // ======================================================================

    /**
     * Da de alta (o cambia) el correo: lo deja como PENDIENTE y manda un
     * código a esa dirección. No toca {@code email} todavía — mientras nadie
     * pruebe que lee ese buzón, la dirección no vale como canal de confianza.
     *
     * El caller ya verificó la contraseña actual.
     */
    public EmailCodeService.Issued startEmailChange(User user, String rawEmail, String ip) {
        String email = EmailAddresses.normalize(rawEmail);
        if (!EmailAddresses.isValid(email)) {
            throw ApiException.badRequest("Ingresá un correo válido");
        }
        if (email.equals(user.getEmail()) && user.isEmailVerified()) {
            throw ApiException.badRequest("Ese ya es el correo de tu cuenta");
        }
        requireEmailAvailable(email, user.getId());

        user.setPendingEmail(email);
        userRepository.save(user);

        return emailCodeService.issueAndSend(user, EmailCode.Purpose.VERIFY_EMAIL, email, ip);
    }

    /**
     * Confirma la dirección pendiente y la promueve a correo de la cuenta.
     *
     * Se promueve la dirección que viene guardada EN EL CÓDIGO, no la que hay
     * en {@code pendingEmail} al momento de confirmar: así, si entre la emisión
     * y la confirmación alguien cambió el pendiente, lo que queda verificado es
     * la dirección que efectivamente recibió el código.
     */
    public void confirmEmailChange(User user, String code) {
        if (user.getPendingEmail() == null || user.getPendingEmail().isBlank()) {
            throw ApiException.badRequest(
                    "No hay ningún correo pendiente de confirmar. Volvé a empezar.");
        }
        String verified = emailCodeService.verifyAndConsume(user, EmailCode.Purpose.VERIFY_EMAIL, code);

        // Se revisa de nuevo la unicidad: entre la emisión y la confirmación
        // otra cuenta pudo quedarse con esa dirección.
        requireEmailAvailable(verified, user.getId());

        String previous = user.isEmailVerified() ? user.getEmail() : null;

        user.setEmail(verified);
        user.setEmailVerified(true);
        user.setPendingEmail(null);
        saveHandlingEmailConflict(user);

        // Aviso a la dirección ANTERIOR. Es la señal que delata un secuestro:
        // quien entra a una cuenta ajena mueve el correo primero, y si el aviso
        // fuera solo a la dirección nueva el dueño real no se enteraría.
        if (previous != null && !previous.equalsIgnoreCase(verified)) {
            mailService.sendAsync(previous,
                    emailTemplates.emailChanged(displayName(user), EmailAddresses.mask(verified)));
        }
    }

    /**
     * Quita el correo de la cuenta. Apaga también el segundo factor por correo:
     * dejarlo activo sin dirección dejaría al usuario sin poder iniciar sesión.
     *
     * El caller ya verificó la contraseña actual.
     */
    public void removeEmail(User user) {
        if (user.getEmail() == null && user.getPendingEmail() == null) {
            throw ApiException.badRequest("No hay ningún correo configurado");
        }
        String previous = user.isEmailVerified() ? user.getEmail() : null;

        user.setEmail(null);
        user.setEmailVerified(false);
        user.setPendingEmail(null);
        user.setEmail2faEnabled(false);
        // El bloqueo por códigos deja de tener sentido sin canal: si no se
        // limpia, quien vuelva a dar de alta un correo se encuentra bloqueado
        // por intentos de un canal que ya no existe.
        user.setFailedEmailCodeAttempts(0);
        user.setEmailCodeLockedUntil(null);
        userRepository.save(user);

        emailCodeService.invalidate(user, EmailCode.Purpose.LOGIN);
        emailCodeService.invalidate(user, EmailCode.Purpose.VERIFY_EMAIL);

        if (previous != null) {
            mailService.sendAsync(previous, emailTemplates.emailRemoved(displayName(user)));
        }
    }

    /**
     * Aplica los cambios de preferencia. Los nulls significan "no tocar": la
     * pantalla manda solo el control que el usuario movió.
     *
     * El caller ya verificó la contraseña cuando el cambio incluye
     * {@code email2faEnabled} (es un cambio de factor de autenticación).
     */
    public void updateEmailPreferences(User user, EmailPreferencesRequest body) {
        boolean silencing = false;
        if (body.getLoginAlerts() != null) {
            silencing = LoginAlertService.ALERTS_OFF.equals(body.getLoginAlerts())
                    && !LoginAlertService.ALERTS_OFF.equals(user.getLoginAlerts());
            user.setLoginAlerts(body.getLoginAlerts());
        }
        if (body.getEmail2faEnabled() != null) {
            if (body.getEmail2faEnabled() && !user.hasUsableEmail()) {
                throw ApiException.badRequest(
                        "Primero confirmá tu correo para poder recibir códigos");
            }
            if (body.getEmail2faEnabled()
                    && !Permissions.canUseEmailAsSecondFactor(user.getRole(), user.getUsername())) {
                throw ApiException.forbidden(
                        "Las cuentas de administración no pueden usar el código por correo como "
                      + "segundo factor: usá la app autenticadora. El correo sigue sirviendo "
                      + "para los avisos de inicio de sesión.");
            }
            user.setEmail2faEnabled(body.getEmail2faEnabled());
        }
        userRepository.save(user);

        // Un último aviso al apagar los avisos. Parece redundante y no lo es:
        // silenciar la notificación es exactamente el primer movimiento de
        // quien entró a una cuenta ajena, y sin este mensaje ese movimiento
        // sería el único que nunca se anuncia.
        if (silencing && user.hasUsableEmail()) {
            mailService.sendAsync(user.getEmail(),
                    emailTemplates.alertsDisabled(displayName(user), LocalDateTime.now()));
        }
    }

    /**
     * Rechaza direcciones ya tomadas. El mensaje es genérico a propósito: uno
     * que dijera "ese correo ya está en uso" convertiría el endpoint en un
     * oráculo para averiguar quién tiene cuenta en el sistema.
     */
    private void requireEmailAvailable(String email, Integer selfId) {
        userRepository.findByEmail(email).ifPresent(other -> {
            if (!other.getId().equals(selfId)) {
                throw ApiException.badRequest("No se puede usar ese correo. Probá con otro.");
            }
        });
    }

    /**
     * El índice único de V9 es la última palabra sobre la unicidad del correo:
     * la comprobación previa puede perder una carrera entre dos altas
     * simultáneas de la misma dirección. Se traduce el error de la base al
     * mismo mensaje genérico para no filtrar por qué falló.
     */
    private void saveHandlingEmailConflict(User user) {
        try {
            userRepository.saveAndFlush(user);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw ApiException.badRequest("No se puede usar ese correo. Probá con otro.");
        }
    }

    private static String displayName(User user) {
        String full = user.getFullName();
        return full != null && !full.isBlank() ? full : user.getUsername();
    }

    public UserDto toDto(User user) {
        UserDto dto = new UserDto();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setRole(user.getRole());
        dto.setFullName(user.getFullName());
        dto.setArea(user.getArea());
        dto.setPosition(user.getPosition());
        dto.setFactory(user.getFactory());
        dto.setContactInfo(user.getContactInfo());
        dto.setProfilePic(user.getProfilePic());
        dto.setTotpEnabled(hasTotp(user));
        dto.setLastSeen(user.getLastSeen());
        dto.setEmail(user.getEmail());
        dto.setEmailVerified(user.isEmailVerified());
        dto.setPendingEmail(user.getPendingEmail());
        dto.setEmail2faEnabled(user.isEmail2faEnabled());
        dto.setEmailFactorAllowed(
                Permissions.canUseEmailAsSecondFactor(user.getRole(), user.getUsername()));
        dto.setLoginAlerts(user.getLoginAlerts());
        return dto;
    }

    /**
     * Public directory view for regular authenticated users. Keeps profile data
     * useful for collaboration but omits privilege and session metadata.
     */
    public UserDto toDirectoryDto(User user) {
        UserDto dto = new UserDto();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setFullName(user.getFullName());
        dto.setArea(user.getArea());
        dto.setPosition(user.getPosition());
        dto.setFactory(user.getFactory());
        dto.setProfilePic(user.getProfilePic());
        return dto;
    }
}
