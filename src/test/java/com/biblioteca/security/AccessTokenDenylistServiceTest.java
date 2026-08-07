package com.biblioteca.security;

import com.biblioteca.model.RevokedAccessToken;
import com.biblioteca.repository.RevokedAccessTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Revocación de access tokens en /logout.
 *
 * Antes, cerrar sesión solo mataba el refresh token: el access seguía válido
 * hasta 15 minutos más. Quien lo hubiera capturado conservaba el acceso durante
 * ese rato, justo lo que el usuario cree cortar al pulsar "cerrar sesión".
 *
 * El requisito que define el diseño es que la revocación sea POR TOKEN: cerrar
 * sesión en un dispositivo no puede echar a la misma persona de los otros. Por
 * eso se revoca `jti` y no una marca por usuario.
 */
class AccessTokenDenylistServiceTest {

    private static final byte[] KEY_BYTES = new byte[64];
    static {
        for (int i = 0; i < KEY_BYTES.length; i++) KEY_BYTES[i] = (byte) (i * 11 + 5);
    }
    private static final String SECRET_B64 = Base64.getEncoder().encodeToString(KEY_BYTES);

    private RevokedAccessTokenRepository repo;
    private JwtTokenProvider tokenProvider;
    private AccessTokenDenylistService service;

    @BeforeEach
    void setUp() {
        repo = mock(RevokedAccessTokenRepository.class);
        tokenProvider = new JwtTokenProvider(SECRET_B64, 900_000L);
        service = new AccessTokenDenylistService(repo, tokenProvider);
    }

    @Test
    void revocarGuardaElJtiDelToken() {
        String token = tokenProvider.generateToken(7, "ana", "user");
        String jti = tokenProvider.getJtiFromToken(token);
        when(repo.existsById(jti)).thenReturn(false);

        service.revoke(token);

        var captor = org.mockito.ArgumentCaptor.forClass(RevokedAccessToken.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getJti()).isEqualTo(jti);
        assertThat(captor.getValue().getExpiresAt()).isNotNull();
    }

    @Test
    void cadaTokenTieneSuPropioJti() {
        // Es lo que permite cerrar UNA sesión sin tocar las demás: si dos
        // logins del mismo usuario compartieran jti, revocar uno mataría ambos.
        String enElTelefono = tokenProvider.generateToken(7, "ana", "user");
        String enLaCompu    = tokenProvider.generateToken(7, "ana", "user");

        assertThat(tokenProvider.getJtiFromToken(enElTelefono))
                .isNotNull()
                .isNotEqualTo(tokenProvider.getJtiFromToken(enLaCompu));
    }

    @Test
    void revocarUnaSesionNoAfectaALaOtra() {
        String enElTelefono = tokenProvider.generateToken(7, "ana", "user");
        String enLaCompu    = tokenProvider.generateToken(7, "ana", "user");
        String jtiTelefono  = tokenProvider.getJtiFromToken(enElTelefono);
        String jtiCompu     = tokenProvider.getJtiFromToken(enLaCompu);

        when(repo.existsById(jtiTelefono)).thenReturn(true);
        when(repo.existsById(jtiCompu)).thenReturn(false);

        assertThat(service.isRevoked(jtiTelefono)).isTrue();
        assertThat(service.isRevoked(jtiCompu)).isFalse();
    }

    @Test
    void noSeGuardaDosVecesElMismoToken() {
        String token = tokenProvider.generateToken(7, "ana", "user");
        when(repo.existsById(anyString())).thenReturn(true);

        service.revoke(token);

        verify(repo, never()).save(any());
    }

    @Test
    void cadaRevocacionLimpiaLosVencidos() {
        // Así la tabla se mantiene diminuta sin necesitar un scheduler.
        String token = tokenProvider.generateToken(7, "ana", "user");
        when(repo.existsById(anyString())).thenReturn(false);

        service.revoke(token);

        verify(repo).deleteExpired(any());
    }

    @Test
    void unTokenBasuraNoRompeElLogout() {
        // El logout NUNCA debe fallar: si tirara, el usuario se quedaría con la
        // sesión abierta justo cuando pidió cerrarla.
        service.revoke("esto-no-es-un-jwt");
        service.revoke("");
        service.revoke(null);

        verify(repo, never()).save(any());
    }

    @Test
    void unTokenSinJtiEsUnNoOp() {
        // Forma de los tokens emitidos antes de que existiera el claim. Se
        // aceptan hasta que expiran (≤15 min): rechazarlos habría cerrado la
        // sesión de todo el mundo al desplegar, sin ganancia de seguridad.
        String sinJti = io.jsonwebtoken.Jwts.builder()
                .subject("7")
                .issuer(JwtTokenProvider.ISSUER)
                .audience().add(JwtTokenProvider.AUDIENCE).and()
                .claim("scope", "access")
                .issuedAt(new java.util.Date())
                .expiration(new java.util.Date(System.currentTimeMillis() + 900_000L))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(KEY_BYTES),
                          io.jsonwebtoken.Jwts.SIG.HS512)
                .compact();

        service.revoke(sinJti);

        verify(repo, never()).save(any());
    }

    @Test
    void unJtiNuloNoConsultaLaBase() {
        assertThat(service.isRevoked(null)).isFalse();
        assertThat(service.isRevoked("")).isFalse();
        verify(repo, never()).existsById(anyString());
    }
}
