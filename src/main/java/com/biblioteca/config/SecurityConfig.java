package com.biblioteca.config;

import com.biblioteca.security.JwtAuthenticationFilter;
import com.biblioteca.security.RateLimitFilter;
import com.biblioteca.security.RestAuthenticationEntryPoint;
import com.biblioteca.security.WerkzeugCompatiblePasswordEncoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;
    private final RateLimitFilter rateLimitFilter;
    private final RestAuthenticationEntryPoint authEntryPoint;

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    public SecurityConfig(JwtAuthenticationFilter jwtFilter,
                          RateLimitFilter rateLimitFilter,
                          RestAuthenticationEntryPoint authEntryPoint) {
        this.jwtFilter = jwtFilter;
        this.rateLimitFilter = rateLimitFilter;
        this.authEntryPoint = authEntryPoint;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsSource()))
            .csrf(csrf -> csrf.disable())
            .headers(headers -> headers
                .frameOptions(frameOptions -> frameOptions.sameOrigin())
                // HSTS — solo se emite sobre HTTPS, así que en dev (http://localhost)
                // no estorba; en prod fuerza el navegador a no volver a hablar HTTP.
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .maxAgeInSeconds(31536000))
                .referrerPolicy(rp -> rp.policy(
                    ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                // Apagamos APIs del navegador que la API no necesita — defensa por
                // si algún día un PDF/HTML servido desde aquí intenta usarlas.
                .permissionsPolicyHeader(pp -> pp.policy(
                    "camera=(), microphone=(), geolocation=(), payment=(), usb=()"))
                // CSP para una API que además sirve archivos subidos por usuarios.
                //
                // Esto NO protege al frontend (vive en otro dominio y trae su propia
                // CSP): protege contra el contenido servido desde ESTE origen. Si
                // alguien logra subir un HTML/SVG y hacer que un navegador lo abra
                // directo desde el dominio de la API, sin CSP ese documento corre
                // con scripts en el origen de la API y puede llamar a sus endpoints.
                //
                // default-src 'none' es el default correcto para un origen que solo
                // devuelve JSON y bytes de archivos: nada de scripts, nada de
                // subrecursos. frame-ancestors 'none' complementa al X-Frame-Options
                // (que los navegadores modernos ya ignoran a favor de la CSP).
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                    "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"))
                // Impide que otro sitio incruste los archivos de la API como
                // subrecurso (<img>, <script>). Las categorías públicas siguen
                // siendo alcanzables por navegación directa, pero dejan de ser
                // hotlinkeables desde un origen ajeno.
                .crossOriginResourcePolicy(corp -> corp.policy(
                    org.springframework.security.web.header.writers
                        .CrossOriginResourcePolicyHeaderWriter.CrossOriginResourcePolicy.SAME_SITE))
            )
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(eh -> eh.authenticationEntryPoint(authEntryPoint))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                // /request-email-code y /verify-email-code van sin sesión por
                // la misma razón que /verify-2fa: ocurren ENTRE la contraseña
                // y la sesión, así que todavía no hay access token. Lo que
                // autoriza es el step token de scope=2fa-pending que valida
                // AuthService, y sin él no hacen nada.
                .requestMatchers("/api/auth/login", "/api/auth/verify-2fa",
                                 "/api/auth/request-email-code", "/api/auth/verify-email-code",
                                 "/api/auth/refresh", "/api/auth/logout").permitAll()
                // /api/files/** exige sesión. Antes era permitAll para que un
                // <img src> del navegador pudiera cargar avatares y boletines
                // sin header Authorization; el frontend ya los descarga
                // autenticados (useAvatarUrl / useAuthenticatedBlob), así que
                // la excepción dejó de hacer falta.
                //
                // Exigirlo ACÁ, y no solo en el controller, es defensa en
                // profundidad: si mañana alguien agrega un endpoint bajo
                // /api/files sin repetir el chequeo, sigue cubierto. El
                // controller conserva las reglas por rol (almacenes).
                .requestMatchers("/api/files/**").authenticated()
                .requestMatchers("/actuator/health", "/actuator/info", "/error").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new WerkzeugCompatiblePasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsSource() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization", "Content-Disposition", "X-Request-Id"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
