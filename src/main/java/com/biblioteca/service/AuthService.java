package com.biblioteca.service;

import com.biblioteca.dto.*;
import com.biblioteca.exception.ApiException;
import com.biblioteca.model.User;
import com.biblioteca.repository.UserRepository;
import com.biblioteca.security.EncryptionService;
import com.biblioteca.security.JwtTokenProvider;
import com.biblioteca.security.RefreshTokenService;
import com.biblioteca.security.TotpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       JwtTokenProvider tokenProvider, TotpService totpService,
                       RefreshTokenService refreshTokenService,
                       EncryptionService encryptionService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.totpService = totpService;
        this.refreshTokenService = refreshTokenService;
        this.encryptionService = encryptionService;
        this.dummyHash = passwordEncoder.encode(java.util.UUID.randomUUID().toString());
    }

    /**
     * Result of a successful authentication. Carries the access token (for the
     * response body) and the raw refresh token (which the controller must put
     * into an httpOnly cookie). For 2FA-pending logins, refreshToken is null
     * and only the step token in {@link LoginResponse} is populated.
     */
    public record AuthResult(LoginResponse body, String refreshToken) {}

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

    public AuthResult login(LoginRequest request, String ip, String userAgent) {
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
            int attempts = user.getFailedPasswordAttempts() + 1;
            user.setFailedPasswordAttempts(attempts);
            if (attempts >= MAX_LOGIN_ATTEMPTS) {
                user.setPasswordLockedUntil(LocalDateTime.now().plus(LOGIN_LOCKOUT));
            }
            userRepository.save(user);
            throw ApiException.unauthorized("Credenciales incorrectas");
        }

        // Contraseña correcta → se limpia el estado de fallos. Se hace ANTES de la
        // rama de 2FA a propósito: quien probó la contraseña ya demostró conocerla,
        // y dejar el contador cargado bloquearía su próximo login legítimo.
        if (user.getFailedPasswordAttempts() != 0 || user.getPasswordLockedUntil() != null) {
            user.setFailedPasswordAttempts(0);
            user.setPasswordLockedUntil(null);
            userRepository.save(user);
        }

        if (user.getTotpSecret() != null && !user.getTotpSecret().isEmpty()) {
            String stepToken = tokenProvider.generate2faStepToken(
                    user.getId(), user.getUsername(), request.isRemember());
            return new AuthResult(
                    LoginResponse.twoFactorPending(stepToken, "Se requiere código 2FA"),
                    null);
        }

        return issueSession(user, request.isRemember(), ip, userAgent);
    }

    public AuthResult verify2fa(Verify2faRequest request, String ip, String userAgent) {
        // Step token MUST be valid and have scope=2fa-pending — proves step 1 completed.
        String stepToken = request.getStepToken();
        if (stepToken == null || !tokenProvider.validateToken(stepToken)) {
            throw ApiException.unauthorized("Sesión 2FA expirada, vuelve a iniciar sesión");
        }
        if (!"2fa-pending".equals(tokenProvider.getScopeFromToken(stepToken))) {
            throw ApiException.unauthorized("Token inválido para 2FA");
        }

        Integer userId = tokenProvider.getUserIdFromToken(stepToken);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.unauthorized("Sesión 2FA inválida"));

        // Se comprueba ANTES de mirar el secret: mientras dura el bloqueo no se
        // verifica ningún código, así que el endpoint tampoco sirve de oráculo.
        enforceTotpLockout(user);

        if (user.getTotpSecret() == null || user.getTotpSecret().isEmpty()) {
            throw ApiException.badRequest("2FA no está configurado para este usuario");
        }

        if (!totpService.verify(encryptionService.decrypt(user.getTotpSecret()), request.getCode())) {
            registerTotpFailure(user);
            throw ApiException.unauthorized("Código incorrecto");
        }
        clearTotpFailures(user);

        // "Remember" was decided at step 1 and is carried in the step token,
        // so it can't be flipped between login and 2FA.
        boolean remember = tokenProvider.getRememberFromToken(stepToken);
        return issueSession(user, remember, ip, userAgent);
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
     * Suma un fallo de TOTP y bloquea al llegar al umbral. El save() es
     * explícito porque la entidad no viaja en una transacción con dirty
     * checking — sin él el contador solo viviría en memoria y el bloqueo
     * no llegaría nunca a existir.
     */
    private void registerTotpFailure(User user) {
        int attempts = user.getFailedTotpAttempts() + 1;
        user.setFailedTotpAttempts(attempts);
        if (attempts >= MAX_TOTP_ATTEMPTS) {
            user.setTotpLockedUntil(LocalDateTime.now().plus(TOTP_LOCKOUT));
            // El 2FA es la última línea de defensa de la cuenta: que se agote
            // merece quedar en el log aunque el bloqueo se venza solo.
            log.warn("TOTP lockout activado para userId={} tras {} fallos", user.getId(), attempts);
        }
        userRepository.save(user);
    }

    /** Limpia el estado de fallos tras un código correcto. */
    private void clearTotpFailures(User user) {
        if (user.getFailedTotpAttempts() != 0 || user.getTotpLockedUntil() != null) {
            user.setFailedTotpAttempts(0);
            user.setTotpLockedUntil(null);
            userRepository.save(user);
        }
    }

    /**
     * Issues an access token. When {@code remember} is true, also issues a
     * refresh token so the session can survive past the 15-minute access TTL;
     * when false, no refresh is issued and the session ends when the access
     * token expires.
     */
    private AuthResult issueSession(User user, boolean remember, String ip, String userAgent) {
        String token = tokenProvider.generateToken(user.getId(), user.getUsername(), user.getRole());
        String refreshToken = remember
                ? refreshTokenService.issue(user.getId(), ip, userAgent)
                : null;
        return new AuthResult(new LoginResponse(token, toDto(user)), refreshToken);
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
        return new AuthResult(new LoginResponse(token, toDto(user)), rotated.rawToken());
    }

    public void logout(String currentRefresh) {
        refreshTokenService.revoke(currentRefresh);
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
     */
    public void clear2fa(User user) {
        user.setTotpSecret(null);
        user.setTotpPendingSecret(null);
        // También se limpia el bloqueo: si el reseteo existe para destrabar a
        // quien perdió el dispositivo, dejarlo bloqueado lo mandaría a esperar
        // 15 minutos para configurar el factor nuevo.
        user.setFailedTotpAttempts(0);
        user.setTotpLockedUntil(null);
        userRepository.save(user);
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
        dto.setTotpEnabled(user.getTotpSecret() != null && !user.getTotpSecret().isEmpty());
        dto.setLastSeen(user.getLastSeen());
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
