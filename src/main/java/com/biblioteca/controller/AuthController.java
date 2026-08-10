package com.biblioteca.controller;

import com.biblioteca.dto.*;
import com.biblioteca.exception.ApiException;
import com.biblioteca.model.AuditLog;
import com.biblioteca.model.User;
import com.biblioteca.repository.AuditLogRepository;
import com.biblioteca.repository.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import com.biblioteca.security.RefreshTokenService;
import com.biblioteca.security.DeviceCookieFactory;
import com.biblioteca.security.Permissions;
import com.biblioteca.security.RefreshCookieFactory;
import com.biblioteca.security.TrustedOriginValidator;
import com.biblioteca.security.UserPrincipal;
import com.biblioteca.service.AuditService;
import com.biblioteca.service.AuthService;
import com.biblioteca.service.EmailCodeService;
import com.biblioteca.service.FileStorageService;
import com.biblioteca.service.LoginAlertService;
import com.biblioteca.service.QrCodeService;
import java.time.Duration;
import java.time.LocalDateTime;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.validation.annotation.Validated;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/auth")
@Validated  // habilita validación en @RequestParam / @PathVariable
public class AuthController {

    private final AuthService authService;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final FileStorageService fileStorageService;
    private final PasswordEncoder passwordEncoder;
    private final QrCodeService qrCodeService;
    private final RefreshCookieFactory refreshCookieFactory;
    private final DeviceCookieFactory deviceCookieFactory;
    private final RefreshTokenService refreshTokenService;
    private final TrustedOriginValidator originValidator;
    private final AuditLogRepository auditLogRepository;

    public AuthController(AuthService authService, UserRepository userRepository,
                          AuditService auditService, FileStorageService fileStorageService,
                          PasswordEncoder passwordEncoder, QrCodeService qrCodeService,
                          RefreshCookieFactory refreshCookieFactory,
                          DeviceCookieFactory deviceCookieFactory,
                          RefreshTokenService refreshTokenService,
                          TrustedOriginValidator originValidator,
                          AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
        this.deviceCookieFactory = deviceCookieFactory;
        this.authService = authService;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.fileStorageService = fileStorageService;
        this.passwordEncoder = passwordEncoder;
        this.qrCodeService = qrCodeService;
        this.refreshCookieFactory = refreshCookieFactory;
        this.refreshTokenService = refreshTokenService;
        this.originValidator = originValidator;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request,
            @CookieValue(value = DeviceCookieFactory.COOKIE_NAME, required = false) String deviceCookie,
            HttpServletRequest req) {
        try {
            AuthService.AuthResult result = authService.login(request, loginContext(req, deviceCookie));
            auditService.log(request.getUsername(), "Login exitoso", req.getRemoteAddr());
            return withAuthCookies(result);
        } catch (ApiException e) {
            auditService.log(request.getUsername(), "Login fallido", req.getRemoteAddr());
            throw e;
        }
    }

    @PostMapping("/verify-2fa")
    public ResponseEntity<LoginResponse> verify2fa(
            @Valid @RequestBody Verify2faRequest request,
            @CookieValue(value = DeviceCookieFactory.COOKIE_NAME, required = false) String deviceCookie,
            HttpServletRequest req) {
        AuthService.AuthResult result = authService.verify2fa(request, loginContext(req, deviceCookie));
        String who = result.body().getUser() != null ? result.body().getUser().getUsername() : "?";
        auditService.log(who, "Login 2FA exitoso", req.getRemoteAddr());
        return withAuthCookies(result);
    }

    /**
     * Envía (o reenvía) el código de acceso durante un login pendiente de 2FA.
     *
     * permitAll con validación por step token: acá todavía no hay sesión. El
     * destinatario del correo lo determina el token, nunca el cuerpo del
     * request — ver {@link RequestEmailCodeRequest}.
     */
    @PostMapping("/request-email-code")
    public ResponseEntity<Map<String, Object>> requestEmailCode(
            @Valid @RequestBody RequestEmailCodeRequest request, HttpServletRequest req) {
        originValidator.requireTrustedOrigin(req);
        EmailCodeService.Issued issued =
                authService.requestLoginCode(request.getStepToken(), req.getRemoteAddr());
        return ResponseEntity.ok(Map.of(
                "message", "Te enviamos un código a tu correo",
                "maskedEmail", issued.maskedDestination(),
                "expiresAt", issued.expiresAt()));
    }

    /** Segundo paso del login cuando el factor elegido es el código por correo. */
    @PostMapping("/verify-email-code")
    public ResponseEntity<LoginResponse> verifyEmailCode(
            @Valid @RequestBody VerifyEmailCodeRequest request,
            @CookieValue(value = DeviceCookieFactory.COOKIE_NAME, required = false) String deviceCookie,
            HttpServletRequest req) {
        AuthService.AuthResult result =
                authService.verifyEmailCode(request, loginContext(req, deviceCookie));
        String who = result.body().getUser() != null ? result.body().getUser().getUsername() : "?";
        auditService.log(who, "Login con código por correo", req.getRemoteAddr());
        return withAuthCookies(result);
    }

    private static AuthService.LoginContext loginContext(HttpServletRequest req, String deviceCookie) {
        return new AuthService.LoginContext(
                req.getRemoteAddr(), req.getHeader("User-Agent"), deviceCookie);
    }

    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(
            @CookieValue(value = RefreshCookieFactory.COOKIE_NAME, required = false) String currentRefresh,
            HttpServletRequest req) {
        // Anti-CSRF para endpoints permitAll con cookie SameSite=None:
        // si llega Origin, debe estar en la allowlist de CORS. Sin Origin
        // (curl, server-to-server) se permite.
        originValidator.requireTrustedOrigin(req);
        AuthService.AuthResult result = authService.refresh(currentRefresh, req.getRemoteAddr(), req.getHeader("User-Agent"));
        return withAuthCookies(result);
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(
            @CookieValue(value = RefreshCookieFactory.COOKIE_NAME, required = false) String currentRefresh,
            HttpServletRequest req) {
        originValidator.requireTrustedOrigin(req);
        // El access token viaja en el header, no en la cookie: se lee acá para
        // que el logout pueda invalidarlo además de matar el refresh token.
        authService.logout(currentRefresh, bearerToken(req));
        ResponseCookie deletion = refreshCookieFactory.buildDeletion();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, deletion.toString())
                .body(Map.of("message", "Sesión cerrada"));
    }

    /** Access token del header Authorization, o null si no vino. */
    private static String bearerToken(HttpServletRequest req) {
        String header = req.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            String raw = header.substring(7).trim();
            return raw.isEmpty() ? null : raw;
        }
        return null;
    }

    /**
     * Envuelve un AuthResult adjuntando las cookies que correspondan:
     *
     *  - refresh token, solo si se emitió uno (no se emite en los logins que
     *    quedan pendientes de 2FA, que solo devuelven el step token, ni cuando
     *    el usuario no pidió "recordarme").
     *  - cookie de dispositivo, solo cuando la respuesta abre sesión. Se
     *    reemite en cada inicio para renovarle el vencimiento.
     */
    private ResponseEntity<LoginResponse> withAuthCookies(AuthService.AuthResult result) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (result.refreshToken() != null) {
            builder.header(HttpHeaders.SET_COOKIE,
                    refreshCookieFactory.build(result.refreshToken()).toString());
        }
        if (result.deviceCookie() != null) {
            builder.header(HttpHeaders.SET_COOKIE,
                    deviceCookieFactory.build(result.deviceCookie()).toString());
        }
        return builder.body(result.body());
    }

    @GetMapping("/me")
    public ResponseEntity<UserDto> me(@AuthenticationPrincipal UserPrincipal principal) {
        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> ApiException.notFound("Usuario no encontrado"));
        // Throttled last_seen update (5 min) via targeted UPDATE — avoids
        // full-row writes and lock contention on concurrent /auth/me calls.
        LocalDateTime now = LocalDateTime.now();
        if (user.getLastSeen() == null
                || Duration.between(user.getLastSeen(), now).toMinutes() >= 5) {
            userRepository.touchLastSeen(user.getId(), now);
            user.setLastSeen(now); // keep DTO in sync
        }
        return ResponseEntity.ok(authService.toDto(user));
    }

    @PostMapping("/setup-2fa")
    public ResponseEntity<Map<String, String>> setup2fa(@AuthenticationPrincipal UserPrincipal principal,
                                                        @Valid @RequestBody Setup2faRequest body) {
        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> ApiException.notFound("Usuario no encontrado"));
        // Require the current password before issuing a fresh secret — a
        // stolen access token alone shouldn't let an attacker take over 2FA.
        verifyCurrentPassword(user, body.getCurrentPassword());
        String secret = authService.setup2fa(user);
        String uri = authService.otpAuthUri(user.getUsername(), secret);
        Map<String, String> resp = new HashMap<>();
        resp.put("secret", secret);
        resp.put("uri", uri);
        resp.put("qr", qrCodeService.toBase64Png(uri, 240));
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/confirm-2fa")
    public ResponseEntity<Map<String, String>> confirm2fa(@AuthenticationPrincipal UserPrincipal principal,
                                                          @Valid @RequestBody Confirm2faRequest body,
                                                          HttpServletRequest req) {
        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> ApiException.notFound("Usuario no encontrado"));
        verifyCurrentPassword(user, body.getCurrentPassword());
        // El secret ya no se toma del body: /setup-2fa lo dejó guardado en el
        // servidor. body.getSecret() se sigue aceptando (el frontend actual lo
        // manda) pero se ignora.
        authService.verifyAndEnable2fa(user, body.getCode());
        auditService.log(user.getUsername(), "2FA activado", req.getRemoteAddr());
        return ResponseEntity.ok(Map.of("message", "2FA activado correctamente"));
    }

    /**
     * Desactiva el 2FA de la propia cuenta. Exige contraseña + código vigente
     * (ver AuthService.disable2fa para el porqué de las dos cosas).
     *
     * La contraseña pasa por el mismo lockout que /change-password: es otro
     * endpoint donde se puede probar contraseñas con un token robado.
     */
    @PostMapping("/disable-2fa")
    public ResponseEntity<Map<String, String>> disable2fa(@AuthenticationPrincipal UserPrincipal principal,
                                                          @Valid @RequestBody Disable2faRequest body,
                                                          HttpServletRequest req) {
        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> ApiException.notFound("Usuario no encontrado"));
        verifyCurrentPasswordWithLockout(user, body.getCurrentPassword());
        authService.disable2fa(user, body.getCode());
        auditService.log(user.getUsername(), "2FA desactivado", req.getRemoteAddr());
        return ResponseEntity.ok(Map.of("message", "Verificación en dos pasos desactivada"));
    }

    /**
     * Reseteo administrativo del 2FA de otro usuario — el caso "perdí el
     * teléfono", donde ni el dueño de la cuenta puede generar un código.
     *
     * Mismo criterio que /change-password: ni siquiera un super_admin puede
     * tocar a un usuario protegido, para que comprometer una cuenta de
     * super_admin no alcance para desarmarle el 2FA a los dueños del sistema.
     */
    @PostMapping("/reset-2fa/{id}")
    public ResponseEntity<Map<String, String>> reset2fa(@PathVariable Integer id,
                                                        @AuthenticationPrincipal UserPrincipal principal,
                                                        HttpServletRequest req) {
        Permissions.requireSuperAdmin(principal);
        User user = userRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Usuario no encontrado"));

        boolean self = principal.getId().equals(id);
        if (!self && Permissions.isProtectedUsername(user.getUsername())) {
            throw ApiException.forbidden("No se puede resetear el 2FA de un usuario protegido");
        }

        authService.clear2fa(user);
        // Las sesiones abiertas del usuario se cierran: si el reseteo se pidió
        // por sospecha de acceso indebido, dejarlas vivas anularía el punto.
        refreshTokenService.revokeAllForUser(user.getId());
        auditService.log(principal.getUsername(),
                "Reseteó el 2FA de " + user.getUsername(), req.getRemoteAddr());
        return ResponseEntity.ok(Map.of("message", "2FA reseteado. El usuario puede configurarlo de nuevo."));
    }

    // ======================================================================
    // Correo de la cuenta
    // ======================================================================

    /**
     * Da de alta o cambia el correo. Queda PENDIENTE hasta que un código
     * enviado a esa dirección vuelva correcto.
     *
     * Exige la contraseña actual, con el mismo lockout que /change-password: el
     * correo pasa a ser canal de segundo factor y de aviso de intrusión, así
     * que un access token robado no puede alcanzar para redirigirlo.
     */
    @PostMapping("/email")
    public ResponseEntity<Map<String, Object>> setEmail(@AuthenticationPrincipal UserPrincipal principal,
                                                        @Valid @RequestBody SetEmailRequest body,
                                                        HttpServletRequest req) {
        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> ApiException.notFound("Usuario no encontrado"));
        verifyCurrentPasswordWithLockout(user, body.getCurrentPassword());

        EmailCodeService.Issued issued =
                authService.startEmailChange(user, body.getEmail(), req.getRemoteAddr());
        auditService.log(user.getUsername(), "Solicitó verificar un correo", req.getRemoteAddr());
        return ResponseEntity.ok(Map.of(
                "message", "Te enviamos un código para confirmar la dirección",
                "maskedEmail", issued.maskedDestination(),
                "expiresAt", issued.expiresAt()));
    }

    /** Confirma la dirección pendiente con el código recibido. */
    @PostMapping("/email/confirm")
    public ResponseEntity<UserDto> confirmEmail(@AuthenticationPrincipal UserPrincipal principal,
                                                @Valid @RequestBody ConfirmEmailRequest body,
                                                HttpServletRequest req) {
        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> ApiException.notFound("Usuario no encontrado"));
        authService.confirmEmailChange(user, body.getCode());
        auditService.log(user.getUsername(), "Confirmó el correo de su cuenta", req.getRemoteAddr());
        return ResponseEntity.ok(authService.toDto(user));
    }

    /**
     * Reenvía el código de confirmación de la dirección pendiente.
     *
     * No pide la contraseña de nuevo: el alta ya la pidió, y el código solo
     * puede ir a la dirección que quedó guardada como pendiente — no hay nada
     * que un atacante pueda redirigir con esta llamada.
     */
    @PostMapping("/email/resend")
    public ResponseEntity<Map<String, Object>> resendEmailCode(
            @AuthenticationPrincipal UserPrincipal principal, HttpServletRequest req) {
        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> ApiException.notFound("Usuario no encontrado"));
        if (user.getPendingEmail() == null || user.getPendingEmail().isBlank()) {
            throw ApiException.badRequest("No hay ningún correo pendiente de confirmar");
        }
        EmailCodeService.Issued issued =
                authService.startEmailChange(user, user.getPendingEmail(), req.getRemoteAddr());
        return ResponseEntity.ok(Map.of(
                "message", "Te reenviamos el código",
                "maskedEmail", issued.maskedDestination(),
                "expiresAt", issued.expiresAt()));
    }

    /**
     * Quita el correo de la cuenta (y con él, el 2FA por correo y los avisos).
     *
     * Va como POST y no como DELETE aunque la semántica REST pida lo segundo.
     * Dos razones prácticas: un DELETE con cuerpo es terreno donde proxies,
     * CDNs y clientes se comportan distinto — y acá el cuerpo lleva la
     * contraseña, así que perderlo convierte la operación en un 400 confuso —,
     * y el limitador de peticiones cubre esta ruta sin excepciones especiales.
     */
    @PostMapping("/email/remove")
    public ResponseEntity<UserDto> removeEmail(@AuthenticationPrincipal UserPrincipal principal,
                                               @Valid @RequestBody RemoveEmailRequest body,
                                               HttpServletRequest req) {
        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> ApiException.notFound("Usuario no encontrado"));
        verifyCurrentPasswordWithLockout(user, body.getCurrentPassword());
        authService.removeEmail(user);
        auditService.log(user.getUsername(), "Quitó el correo de su cuenta", req.getRemoteAddr());
        return ResponseEntity.ok(authService.toDto(user));
    }

    /**
     * Preferencias del correo: avisos de inicio de sesión y segundo factor.
     *
     * La regla es asimétrica y a propósito: TODO cambio que DEBILITE la
     * protección de la cuenta exige la contraseña; los que la refuerzan, no.
     *
     * Debilitan, y por lo tanto la piden:
     *   - activar o desactivar el segundo factor por correo;
     *   - apagar los avisos de inicio de sesión.
     *
     * Lo segundo no estaba en el diseño original y es un agujero real: el
     * aviso existe justamente para delatar a quien ya consiguió entrar, y sin
     * este requisito bastaba un access token robado para silenciarlo antes de
     * hacer nada. Subir el nivel de avisos sigue siendo libre — pedir la
     * contraseña para eso solo consigue que nadie lo configure.
     */
    @PutMapping("/email/preferences")
    public ResponseEntity<UserDto> updateEmailPreferences(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody EmailPreferencesRequest body,
            HttpServletRequest req) {
        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> ApiException.notFound("Usuario no encontrado"));

        boolean togglesFactor = body.getEmail2faEnabled() != null
                && body.getEmail2faEnabled() != user.isEmail2faEnabled();
        boolean silencesAlerts = LoginAlertService.ALERTS_OFF.equals(body.getLoginAlerts())
                && !LoginAlertService.ALERTS_OFF.equals(user.getLoginAlerts());

        if (togglesFactor || silencesAlerts) {
            verifyCurrentPasswordWithLockout(user, body.getCurrentPassword());
        }

        authService.updateEmailPreferences(user, body);

        if (togglesFactor) {
            auditService.log(user.getUsername(),
                    body.getEmail2faEnabled() ? "Activó el código por correo"
                                              : "Desactivó el código por correo",
                    req.getRemoteAddr());
        }
        if (silencesAlerts) {
            // Queda en bitácora aunque el correo de aviso falle: son dos
            // canales independientes para el mismo hecho, y este es el que no
            // depende de un proveedor externo.
            auditService.log(user.getUsername(),
                    "Desactivó los avisos de inicio de sesión", req.getRemoteAddr());
        }
        return ResponseEntity.ok(authService.toDto(user));
    }

    /**
     * Sesiones abiertas de la propia cuenta, para que el usuario pueda detectar
     * un acceso que no reconoce.
     */
    @GetMapping("/sessions")
    public ResponseEntity<List<SessionDto>> sessions(
            @AuthenticationPrincipal UserPrincipal principal,
            @CookieValue(value = RefreshCookieFactory.COOKIE_NAME, required = false) String currentRefresh) {
        Long currentId = refreshTokenService.sessionIdOf(currentRefresh);
        List<SessionDto> sessions = refreshTokenService.activeSessions(principal.getId()).stream()
                .map(rt -> {
                    SessionDto dto = new SessionDto();
                    dto.setIp(rt.getIp());
                    dto.setUserAgent(rt.getUserAgent());
                    dto.setCreatedAt(rt.getCreatedAt());
                    dto.setExpiresAt(rt.getExpiresAt());
                    dto.setCurrent(currentId != null && currentId.equals(rt.getId()));
                    return dto;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(sessions);
    }

    /** Cierra todas las sesiones del usuario menos la actual. */
    @PostMapping("/sessions/revoke-others")
    public ResponseEntity<Map<String, Object>> revokeOtherSessions(
            @AuthenticationPrincipal UserPrincipal principal,
            @CookieValue(value = RefreshCookieFactory.COOKIE_NAME, required = false) String currentRefresh,
            HttpServletRequest req) {
        Long currentId = refreshTokenService.sessionIdOf(currentRefresh);
        int closed = refreshTokenService.revokeOthers(principal.getId(), currentId);
        auditService.log(principal.getUsername(),
                "Cerró " + closed + " sesión(es) remota(s)", req.getRemoteAddr());

        // Sin cookie de refresh no hay forma de saber cuál sesión es la actual,
        // así que se cierran todas — es la opción segura, pero rompe la promesa
        // de "la sesión actual sigue abierta". Se avisa para que el frontend
        // pueda mandar al login en vez de dejar una pantalla que ya no responde.
        boolean keptCurrent = currentId != null;
        return ResponseEntity.ok(Map.of(
                "message", closed == 0 ? "No había otras sesiones abiertas" : "Sesiones cerradas",
                "closed", closed,
                "keptCurrent", keptCurrent));
    }

    /**
     * Últimos movimientos de la propia cuenta. La bitácora completa sigue
     * siendo de super_admin (ver AuditLogController): esto filtra por el
     * usuario autenticado, nunca recibe de quién listar.
     */
    @GetMapping("/activity")
    public ResponseEntity<PagedResponse<AuditLog>> myActivity(
            @AuthenticationPrincipal UserPrincipal principal,
            @PageableDefault(size = 10) Pageable pageable) {
        // Paginado porque la bitácora crece sin techo: las cuentas con más uso
        // ya rondan los 100 eventos y traerlos todos para mostrar los últimos
        // diez es tirar trabajo y ancho de banda a la basura.
        //
        // El orden lo fija el nombre del método (OrderByCreatedAtDesc), no el
        // Pageable: así un ?sort= en la URL no puede reordenar la bitácora ni
        // apuntar a una columna que no corresponde.
        return ResponseEntity.ok(PagedResponse.from(
                auditLogRepository.findByUserOrderByCreatedAtDesc(
                        principal.getUsername(),
                        PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), 50)))));
    }

    private void verifyCurrentPassword(User user, String current) {
        if (current == null || current.isBlank()) {
            throw ApiException.badRequest("Ingresa tu contraseña actual");
        }
        if (!passwordEncoder.matches(current, user.getPasswordHash())) {
            throw ApiException.unauthorized("Contraseña actual incorrecta");
        }
    }

    // Lockout por usuario para /change-password (defensa contra brute-force
    // de currentPassword usando un access token robado). Después de 5 fallos
    // consecutivos bloqueamos durante 15 minutos. Cualquier éxito (verificación
    // OK) resetea el contador. Solo aplica a self-change — el super_admin
    // cambiando contraseñas ajenas no pasa por este flujo (no usa currentPassword).
    private static final int MAX_PASSWORD_ATTEMPTS = 5;
    private static final Duration PASSWORD_LOCKOUT = Duration.ofMinutes(15);

    private void enforcePasswordLockout(User user) {
        LocalDateTime until = user.getPasswordLockedUntil();
        if (until != null && until.isAfter(LocalDateTime.now())) {
            throw ApiException.forbidden(
                "Demasiados intentos fallidos. Intenta de nuevo más tarde.");
        }
    }

    private void verifyCurrentPasswordWithLockout(User user, String current) {
        enforcePasswordLockout(user);
        if (current == null || current.isBlank()) {
            throw ApiException.badRequest("Ingresa tu contraseña actual");
        }
        if (!passwordEncoder.matches(current, user.getPasswordHash())) {
            // Contador e imposición del bloqueo van contra la base de forma
            // atómica (ver el bloque de comentarios de UserRepository): con un
            // save() de la entidad, varios intentos concurrentes escriben todos
            // el mismo valor y el techo de 5 deja de existir.
            authService.registerPasswordFailure(user, MAX_PASSWORD_ATTEMPTS, PASSWORD_LOCKOUT);
            throw ApiException.unauthorized("Contraseña actual incorrecta");
        }
        // Éxito → reset si traía estado. La escritura explícita importa: en
        // /change-password el guardado posterior de la contraseña lo arrastraba,
        // pero en /disable-2fa el flujo sigue con la verificación del código
        // TOTP, que lanza si el código es incorrecto — y el reset se perdía sin
        // llegar nunca a la BD.
        authService.clearPasswordFailures(user);
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register(@Valid @RequestBody RegisterRequest body,
                                                        HttpServletRequest req,
                                                        @AuthenticationPrincipal UserPrincipal principal) {
        Permissions.requireSuperAdmin(principal);

        String username = body.getUsername();
        String role = body.getRole() != null && !body.getRole().isBlank() ? body.getRole() : Permissions.ROLE_USER;

        if (!Permissions.VALID_ROLES.contains(role)) {
            throw ApiException.badRequest("Rol inválido: " + role);
        }
        if (userRepository.existsByUsername(username)) {
            throw ApiException.badRequest("El usuario ya existe");
        }

        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(body.getPassword()));
        user.setRole(role);
        userRepository.save(user);
        auditService.log(principal.getUsername(), "Creó usuario " + username, req.getRemoteAddr());
        return ResponseEntity.ok(Map.of("message", "Usuario creado exitosamente"));
    }

    @GetMapping("/users")
    public ResponseEntity<List<UserDto>> listUsers(@AuthenticationPrincipal UserPrincipal principal) {
        // List is needed for both admin panel (super_admin) and user directory (any logged-in).
        // The frontend Usuarios page only shows management actions for super_admin.
        boolean includeSensitive = Permissions.isSuperAdmin(principal);
        List<User> users = userRepository.findAll();
        List<UserDto> dtos = users.stream()
                .map(u -> includeSensitive ? authService.toDto(u) : authService.toDirectoryDto(u))
                .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/users/{id}")
    public ResponseEntity<UserDto> getUser(@PathVariable Integer id,
                                           @AuthenticationPrincipal UserPrincipal principal) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Usuario no encontrado"));
        boolean includeSensitive = Permissions.isSuperAdmin(principal) || principal.getId().equals(id);
        return ResponseEntity.ok(includeSensitive ? authService.toDto(user) : authService.toDirectoryDto(user));
    }

    // Regex ultra-permisivo: solo bloquea < y > para que un nombre no pueda
    // contener una etiqueta HTML literal. Acentos, espacios, números, puntuación
    // normal entran limpio. React ya escapa en pantalla; esto es defensa en
    // profundidad por si algún día el campo se renderiza en PDF, email, o
    // cualquier contexto que no auto-escape.
    private static final String SAFE_TEXT_REGEX = "[^<>]*";

    @PostMapping("/profile")
    public ResponseEntity<UserDto> updateProfile(@AuthenticationPrincipal UserPrincipal principal,
                                                 @RequestParam(required = false) @Size(max = 150) @Pattern(regexp = SAFE_TEXT_REGEX, message = "Caracteres no permitidos") String fullName,
                                                 @RequestParam(required = false) @Size(max = 100) @Pattern(regexp = SAFE_TEXT_REGEX, message = "Caracteres no permitidos") String area,
                                                 @RequestParam(required = false) @Size(max = 100) @Pattern(regexp = SAFE_TEXT_REGEX, message = "Caracteres no permitidos") String position,
                                                 @RequestParam(required = false) @Size(max = 100) @Pattern(regexp = SAFE_TEXT_REGEX, message = "Caracteres no permitidos") String factory,
                                                 @RequestParam(required = false) @Size(max = 200) @Pattern(regexp = SAFE_TEXT_REGEX, message = "Caracteres no permitidos") String contactInfo,
                                                 @RequestParam(required = false) MultipartFile profilePic) {
        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> ApiException.notFound("Usuario no encontrado"));
        if (fullName != null) user.setFullName(fullName);
        if (area != null) user.setArea(area);
        if (position != null) user.setPosition(position);
        if (factory != null) user.setFactory(factory);
        if (contactInfo != null) user.setContactInfo(contactInfo);
        if (profilePic != null && !profilePic.isEmpty()) {
            String filename = fileStorageService.store(profilePic, "perfiles");
            user.setProfilePic(filename);
        }
        userRepository.save(user);
        return ResponseEntity.ok(authService.toDto(user));
    }

    @PostMapping("/change-password/{id}")
    public ResponseEntity<Map<String, String>> changePassword(@PathVariable Integer id,
                                                              @Valid @RequestBody ChangePasswordRequest body,
                                                              @AuthenticationPrincipal UserPrincipal principal,
                                                              HttpServletRequest req) {
        boolean self = principal.getId().equals(id);
        if (!self && !Permissions.isSuperAdmin(principal)) {
            throw ApiException.forbidden("No tienes permiso para cambiar esta contraseña");
        }
        User user = userRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Usuario no encontrado"));

        // Even super_admin cannot change protected users' password
        if (!self && Permissions.isProtectedUsername(user.getUsername())) {
            throw ApiException.forbidden("No se puede cambiar la contraseña de un usuario protegido");
        }

        // Self-change: require current password — prevents takeover via stolen JWT.
        // Con lockout por usuario: 5 fallos consecutivos → 15 min bloqueado.
        if (self) {
            verifyCurrentPasswordWithLockout(user, body.getCurrentPassword());
        }

        user.setPasswordHash(passwordEncoder.encode(body.getNewPassword()));
        user.setPasswordChangedAt(LocalDateTime.now());
        userRepository.save(user);
        // JwtAuthenticationFilter re-lee password_changed_at de la BD en cada request,
        // así que el nuevo timestamp surte efecto inmediato sin necesidad de invalidar caches.
        // Mata todos los refresh tokens — uno robado no debe sobrevivir el cambio.
        refreshTokenService.revokeAllForUser(user.getId());
        auditService.log(principal.getUsername(),
                "Cambió contraseña de " + user.getUsername(), req.getRemoteAddr());
        return ResponseEntity.ok(Map.of("message", "Contraseña actualizada"));
    }

    @PostMapping("/delete-user/{id}")
    public ResponseEntity<Map<String, String>> deleteUser(@PathVariable Integer id,
                                                          @AuthenticationPrincipal UserPrincipal principal,
                                                          HttpServletRequest req) {
        Permissions.requireSuperAdmin(principal);

        User user = userRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Usuario no encontrado"));

        if (Permissions.isProtectedUsername(user.getUsername())) {
            throw ApiException.forbidden("No se puede eliminar a un usuario protegido");
        }
        if (user.getId().equals(principal.getId())) {
            throw ApiException.badRequest("No puedes eliminarte a ti mismo");
        }
        userRepository.delete(user);
        auditService.log(principal.getUsername(),
                "Eliminó usuario " + user.getUsername(), req.getRemoteAddr());
        return ResponseEntity.ok(Map.of("message", "Usuario eliminado"));
    }
}
