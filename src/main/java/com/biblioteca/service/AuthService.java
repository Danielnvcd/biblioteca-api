package com.biblioteca.service;

import com.biblioteca.dto.*;
import com.biblioteca.exception.ApiException;
import com.biblioteca.model.User;
import com.biblioteca.repository.UserRepository;
import com.biblioteca.security.JwtTokenProvider;
import com.biblioteca.security.TotpService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final TotpService totpService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       JwtTokenProvider tokenProvider, TotpService totpService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.totpService = totpService;
    }

    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> ApiException.unauthorized("Credenciales incorrectas"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw ApiException.unauthorized("Credenciales incorrectas");
        }

        if (user.getTotpSecret() != null && !user.getTotpSecret().isEmpty()) {
            return new LoginResponse(true, "Se requiere código 2FA");
        }

        String token = tokenProvider.generateToken(user.getId(), user.getUsername(), user.getRole());
        return new LoginResponse(token, toDto(user));
    }

    public LoginResponse verify2fa(Verify2faRequest request) {
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> ApiException.unauthorized("Usuario no encontrado o 2FA no configurado"));

        if (user.getTotpSecret() == null || user.getTotpSecret().isEmpty()) {
            throw ApiException.badRequest("2FA no está configurado para este usuario");
        }

        if (!totpService.verify(user.getTotpSecret(), request.getCode())) {
            throw ApiException.unauthorized("Código incorrecto");
        }

        String token = tokenProvider.generateToken(user.getId(), user.getUsername(), user.getRole());
        return new LoginResponse(token, toDto(user));
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
        user.setTotpSecret(secret);
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
