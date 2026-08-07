package com.biblioteca.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica que `iss` y `aud` no solo se emitan, sino que se VALIDEN.
 *
 * Es el modo de fallo típico de estos dos claims: se agregan al builder, el
 * token los lleva, y nadie nota que el parser nunca los mira — con lo cual no
 * aportan nada. Estos tests firman tokens con la MISMA clave y solo cambian el
 * issuer o el audience: si la validación no existiera, pasarían igual.
 */
class JwtTokenProviderClaimsTest {

    private static final byte[] KEY_BYTES = new byte[64]; // HS512 requiere >= 512 bits
    static {
        for (int i = 0; i < KEY_BYTES.length; i++) KEY_BYTES[i] = (byte) (i * 7 + 3);
    }
    private static final String SECRET_B64 = Base64.getEncoder().encodeToString(KEY_BYTES);
    private static final SecretKey KEY = Keys.hmacShaKeyFor(KEY_BYTES);

    private final JwtTokenProvider provider = new JwtTokenProvider(SECRET_B64, 900_000L);

    /** Token firmado con la clave buena, variando issuer/audience a voluntad. */
    private static String forge(String issuer, String audience) {
        Date now = new Date();
        var builder = Jwts.builder()
                .subject("7")
                .claim("username", "ana")
                .claim("role", "user")
                .claim("scope", "access")
                .issuedAt(now)
                .expiration(new Date(now.getTime() + 900_000L));
        if (issuer != null) builder.issuer(issuer);
        if (audience != null) builder.audience().add(audience).and();
        return builder.signWith(KEY, Jwts.SIG.HS512).compact();
    }

    @Test
    void elTokenQueEmiteLaAppSeValidaASiMismo() {
        String token = provider.generateToken(7, "ana", "user");

        assertThat(provider.validateToken(token)).isTrue();
        assertThat(provider.getUserIdFromToken(token)).isEqualTo(7);
        assertThat(provider.getRoleFromToken(token)).isEqualTo("user");
    }

    @Test
    void elStepTokenDe2faTambienLlevaIssuerYAudience() {
        String step = provider.generate2faStepToken(7, "ana", true);

        assertThat(provider.validateToken(step)).isTrue();
        assertThat(provider.getScopeFromToken(step)).isEqualTo("2fa-pending");
    }

    @Test
    void seRechazaUnTokenConOtroIssuer() {
        // Firmado con la clave correcta: lo único distinto es el issuer. Si el
        // parser no lo exigiera, este token entraría.
        String ajeno = forge("otro-servicio", JwtTokenProvider.AUDIENCE);

        assertThat(provider.validateToken(ajeno)).isFalse();
    }

    @Test
    void seRechazaUnTokenConOtraAudience() {
        // El caso que motiva el claim: la misma clave compartida con un segundo
        // servicio, cuyo token no debe servir para entrar acá.
        String paraOtro = forge(JwtTokenProvider.ISSUER, "otra-app");

        assertThat(provider.validateToken(paraOtro)).isFalse();
    }

    @Test
    void seRechazaUnTokenSinEsosClaims() {
        // Forma de los tokens anteriores a este cambio. Se rechazan a propósito;
        // el cliente renueva vía /api/auth/refresh, que no usa JWT.
        assertThat(provider.validateToken(forge(null, null))).isFalse();
    }

    @Test
    void seSigueRechazandoUnaFirmaInvalida() {
        // Guardarraíl: que el token traiga iss/aud correctos no puede volver
        // opcional la verificación de firma.
        byte[] otraClave = new byte[64];
        java.util.Arrays.fill(otraClave, (byte) 0x5A);
        String malFirmado = Jwts.builder()
                .subject("7")
                .issuer(JwtTokenProvider.ISSUER)
                .audience().add(JwtTokenProvider.AUDIENCE).and()
                .claim("scope", "access")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 900_000L))
                .signWith(Keys.hmacShaKeyFor(otraClave), Jwts.SIG.HS512)
                .compact();

        assertThat(provider.validateToken(malFirmado)).isFalse();
    }

    @Test
    void seRechazaUnTokenExpirado() {
        Date pasado = new Date(System.currentTimeMillis() - 3_600_000L);
        String vencido = Jwts.builder()
                .subject("7")
                .issuer(JwtTokenProvider.ISSUER)
                .audience().add(JwtTokenProvider.AUDIENCE).and()
                .claim("scope", "access")
                .issuedAt(pasado)
                .expiration(new Date(pasado.getTime() + 1000L))
                .signWith(KEY, Jwts.SIG.HS512)
                .compact();

        assertThat(provider.validateToken(vencido)).isFalse();
    }
}
