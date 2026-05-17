package com.biblioteca.service;

import com.biblioteca.dto.*;
import com.biblioteca.exception.ApiException;
import com.biblioteca.model.User;
import com.biblioteca.repository.UserRepository;
import com.biblioteca.security.EncryptionService;
import com.biblioteca.security.JwtTokenProvider;
import com.biblioteca.security.RefreshTokenService;
import com.biblioteca.security.TotpService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

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
    }

    /**
     * Result of a successful authentication. Carries the access token (for the
     * response body) and the raw refresh token (which the controller must put
     * into an httpOnly cookie). For 2FA-pending logins, refreshToken is null
     * and only the step token in {@link LoginResponse} is populated.
     */
    public record AuthResult(LoginResponse body, String refreshToken) {}

    public AuthResult login(LoginRequest request, String ip, String userAgent) {
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> ApiException.unauthorized("Credenciales incorrectas"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw ApiException.unauthorized("Credenciales incorrectas");
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

        if (user.getTotpSecret() == null || user.getTotpSecret().isEmpty()) {
            throw ApiException.badRequest("2FA no está configurado para este usuario");
        }

        if (!totpService.verify(encryptionService.decrypt(user.getTotpSecret()), request.getCode())) {
            throw ApiException.unauthorized("Código incorrecto");
        }

        // "Remember" was decided at step 1 and is carried in the step token,
        // so it can't be flipped between login and 2FA.
        boolean remember = tokenProvider.getRememberFromToken(stepToken);
        return issueSession(user, remember, ip, userAgent);
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

    public String setup2fa(User user) {
        return totpService.newSecret();
    }

    public String otpAuthUri(String username, String secret) {
        return totpService.otpAuthUri("Maxipet", username, secret);
    }

    public void verifyAndEnable2fa(User user, String code, String secret) {
        if (secret == null || secret.isBlank()) {
            throw ApiException.badRequest("Falta el secret");
        }
        if (!totpService.verify(secret, code)) {
            throw ApiException.badRequest("Código incorrecto, intenta de nuevo");
        }
        // Encrypt at rest — DB leak shouldn't grant attackers valid TOTP codes.
        user.setTotpSecret(encryptionService.encrypt(secret));
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
}
