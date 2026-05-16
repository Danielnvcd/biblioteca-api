package com.biblioteca.controller;

import com.biblioteca.dto.*;
import com.biblioteca.exception.ApiException;
import com.biblioteca.model.User;
import com.biblioteca.repository.UserRepository;
import com.biblioteca.security.RefreshTokenService;
import com.biblioteca.security.Permissions;
import com.biblioteca.security.RefreshCookieFactory;
import com.biblioteca.security.SessionInvalidationService;
import com.biblioteca.security.UserPrincipal;
import com.biblioteca.service.AuditService;
import com.biblioteca.service.AuthService;
import com.biblioteca.service.FileStorageService;
import com.biblioteca.service.QrCodeService;
import java.time.Duration;
import java.time.LocalDateTime;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
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
public class AuthController {

    private final AuthService authService;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final FileStorageService fileStorageService;
    private final PasswordEncoder passwordEncoder;
    private final QrCodeService qrCodeService;
    private final SessionInvalidationService sessions;
    private final RefreshCookieFactory refreshCookieFactory;
    private final RefreshTokenService refreshTokenService;

    public AuthController(AuthService authService, UserRepository userRepository,
                          AuditService auditService, FileStorageService fileStorageService,
                          PasswordEncoder passwordEncoder, QrCodeService qrCodeService,
                          SessionInvalidationService sessions,
                          RefreshCookieFactory refreshCookieFactory,
                          RefreshTokenService refreshTokenService) {
        this.authService = authService;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.fileStorageService = fileStorageService;
        this.passwordEncoder = passwordEncoder;
        this.qrCodeService = qrCodeService;
        this.sessions = sessions;
        this.refreshCookieFactory = refreshCookieFactory;
        this.refreshTokenService = refreshTokenService;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest req) {
        try {
            AuthService.AuthResult result = authService.login(request, req.getRemoteAddr(), req.getHeader("User-Agent"));
            auditService.log(request.getUsername(), "Login exitoso", req.getRemoteAddr());
            return withOptionalRefreshCookie(result);
        } catch (ApiException e) {
            auditService.log(request.getUsername(), "Login fallido", req.getRemoteAddr());
            throw e;
        }
    }

    @PostMapping("/verify-2fa")
    public ResponseEntity<LoginResponse> verify2fa(@Valid @RequestBody Verify2faRequest request, HttpServletRequest req) {
        AuthService.AuthResult result = authService.verify2fa(request, req.getRemoteAddr(), req.getHeader("User-Agent"));
        String who = result.body().getUser() != null ? result.body().getUser().getUsername() : "?";
        auditService.log(who, "Login 2FA exitoso", req.getRemoteAddr());
        return withOptionalRefreshCookie(result);
    }

    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(
            @CookieValue(value = RefreshCookieFactory.COOKIE_NAME, required = false) String currentRefresh,
            HttpServletRequest req) {
        AuthService.AuthResult result = authService.refresh(currentRefresh, req.getRemoteAddr(), req.getHeader("User-Agent"));
        return withOptionalRefreshCookie(result);
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(
            @CookieValue(value = RefreshCookieFactory.COOKIE_NAME, required = false) String currentRefresh) {
        authService.logout(currentRefresh);
        ResponseCookie deletion = refreshCookieFactory.buildDeletion();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, deletion.toString())
                .body(Map.of("message", "Sesión cerrada"));
    }

    /**
     * Wraps an AuthResult in a ResponseEntity, attaching the refresh cookie
     * if one was issued (skipped for 2FA-pending logins, which only return a
     * step token).
     */
    private ResponseEntity<LoginResponse> withOptionalRefreshCookie(AuthService.AuthResult result) {
        if (result.refreshToken() == null) {
            return ResponseEntity.ok(result.body());
        }
        ResponseCookie cookie = refreshCookieFactory.build(result.refreshToken());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(result.body());
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
    public ResponseEntity<Map<String, String>> setup2fa(@AuthenticationPrincipal UserPrincipal principal) {
        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> ApiException.notFound("Usuario no encontrado"));
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
                                                          @RequestBody Map<String, String> body,
                                                          HttpServletRequest req) {
        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> ApiException.notFound("Usuario no encontrado"));
        authService.verifyAndEnable2fa(user, body.get("code"), body.get("secret"));
        auditService.log(user.getUsername(), "2FA activado", req.getRemoteAddr());
        return ResponseEntity.ok(Map.of("message", "2FA activado correctamente"));
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register(@RequestBody Map<String, String> body, HttpServletRequest req,
                                                        @AuthenticationPrincipal UserPrincipal principal) {
        Permissions.requireSuperAdmin(principal);

        String username = body.get("username");
        String password = body.get("password");
        String role = body.getOrDefault("role", Permissions.ROLE_USER);

        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw ApiException.badRequest("Usuario y contraseña son requeridos");
        }
        if (username.length() > 80) {
            throw ApiException.badRequest("Usuario demasiado largo (máx 80)");
        }
        if (password.length() < 6) {
            throw ApiException.badRequest("La contraseña debe tener al menos 6 caracteres");
        }
        if (!Permissions.VALID_ROLES.contains(role)) {
            throw ApiException.badRequest("Rol inválido: " + role);
        }
        if (userRepository.existsByUsername(username)) {
            throw ApiException.badRequest("El usuario ya existe");
        }

        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole(role);
        userRepository.save(user);
        auditService.log(principal.getUsername(), "Creó usuario " + username, req.getRemoteAddr());
        return ResponseEntity.ok(Map.of("message", "Usuario creado exitosamente"));
    }

    @GetMapping("/users")
    public ResponseEntity<List<UserDto>> listUsers(@AuthenticationPrincipal UserPrincipal principal) {
        // List is needed for both admin panel (super_admin) and user directory (any logged-in).
        // The frontend Usuarios page only shows management actions for super_admin.
        List<User> users = userRepository.findAll();
        List<UserDto> dtos = users.stream().map(authService::toDto).collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/users/{id}")
    public ResponseEntity<UserDto> getUser(@PathVariable Integer id) {
        // Public profile (any authenticated user). UserDto excludes password_hash.
        User user = userRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Usuario no encontrado"));
        return ResponseEntity.ok(authService.toDto(user));
    }

    @PostMapping("/profile")
    public ResponseEntity<UserDto> updateProfile(@AuthenticationPrincipal UserPrincipal principal,
                                                 @RequestParam(required = false) String fullName,
                                                 @RequestParam(required = false) String area,
                                                 @RequestParam(required = false) String position,
                                                 @RequestParam(required = false) String factory,
                                                 @RequestParam(required = false) String contactInfo,
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
                                                              @RequestBody Map<String, String> body,
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
        if (self) {
            String current = body.get("currentPassword");
            if (current == null || current.isBlank()) {
                throw ApiException.badRequest("Ingresa tu contraseña actual");
            }
            if (!passwordEncoder.matches(current, user.getPasswordHash())) {
                throw ApiException.unauthorized("Contraseña actual incorrecta");
            }
        }

        String newPassword = body.get("newPassword");
        if (newPassword == null || newPassword.isBlank()) {
            throw ApiException.badRequest("La contraseña no puede estar vacía");
        }
        if (newPassword.length() < 6) {
            throw ApiException.badRequest("La contraseña debe tener al menos 6 caracteres");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordChangedAt(LocalDateTime.now());
        userRepository.save(user);
        sessions.invalidate(user.getId());   // bust the cache so the new pca takes effect immediately
        // Also kill every refresh token — a stolen one shouldn't outlive the password change.
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
