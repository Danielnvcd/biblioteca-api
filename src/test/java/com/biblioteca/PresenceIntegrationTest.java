package com.biblioteca;

import com.biblioteca.model.User;
import com.biblioteca.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prueba de punta a punta del indicador "en línea", contra un Postgres real.
 *
 * Los tests unitarios de SessionInvalidationServiceActivityTest cubren la
 * decisión de refrescar o no. Lo que NO pueden cubrir, porque trabajan con
 * mocks, es justamente lo que se rompió en producción: que alguien llame a
 * markActive en el request real. Antes de este cambio, la marca vivía solo en
 * /auth/me y el resto de la aplicación jamás la tocaba.
 *
 * Por eso acá se atraviesa la cadena completa — login real, token real, filtro
 * real, UPDATE real — y se mira la columna en la base.
 *
 * Corre contra Postgres y no contra H2 a propósito: las migraciones de Flyway
 * son de Postgres, así que este test también verifica que apliquen limpio sobre
 * una base vacía.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PresenceIntegrationTest {

    @Container
    @SuppressWarnings("resource") // lo cierra Testcontainers al terminar la clase
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("biblioteca_test")
                    .withUsername("bibliotecario")
                    .withPassword("test");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Valores de juguete: el contenedor se destruye al terminar la clase.
        // El secreto tiene que decodificar a 64 bytes o más — HS512 rechaza
        // claves más cortas y el login falla con 500 sin decir por qué.
        registry.add("app.jwt.secret", () ->
                "Y2xhdmUtZGUtcHJ1ZWJhLXBhcmEtdGVzdHMtZGUtaW50ZWdyYWNpb24tc29sby12YWxlLWVuLXRlc3Rjb250YWluZXJzLW51bmNhLWVuLXByb2R1Y2Npb24=");
        registry.add("app.encryption.key", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        registry.add("app.rate-limit.enabled", () -> "false");
    }

    private static final String PASSWORD = "prueba1234";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;

    private Integer userId;

    @BeforeEach
    void crearUsuarioConPresenciaVieja() {
        userRepository.deleteAll();
        User u = new User();
        u.setUsername("presencia");
        u.setPasswordHash(passwordEncoder.encode(PASSWORD));
        u.setRole("user");
        // Arranca "desconectado": última actividad muy por fuera de la ventana.
        u.setLastSeen(LocalDateTime.now().minusHours(3));
        userId = userRepository.save(u).getId();
    }

    /** Login real contra el endpoint; devuelve el access token. */
    private String login() throws Exception {
        String body = objectMapper.writeValueAsString(
                java.util.Map.of("username", "presencia", "password", PASSWORD));
        String json = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(json);
        return node.get("token").asText();
    }

    private LocalDateTime lastSeenEnBase() {
        return userRepository.findById(userId).orElseThrow().getLastSeen();
    }

    @Test
    void unRequestCualquieraMarcaAlUsuarioComoActivo() throws Exception {
        String token = login();
        LocalDateTime antes = lastSeenEnBase();

        // A propósito NO se usa /auth/me: ese endpoint ya refrescaba last_seen
        // por su cuenta desde antes, y el bug era que fuera el único. Se pega a
        // otro endpoint cualquiera para probar que ahora sirve todo el tráfico.
        mockMvc.perform(get("/api/auth/sessions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        LocalDateTime despues = lastSeenEnBase();
        assertThat(despues)
                .as("un request autenticado tiene que contar como actividad")
                .isAfter(antes);
        assertThat(despues).isAfter(LocalDateTime.now().minusMinutes(1));
    }

    @Test
    void elSegundoRequestSeguidoNoVuelveAEscribir() throws Exception {
        String token = login();

        mockMvc.perform(get("/api/auth/sessions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        LocalDateTime primera = lastSeenEnBase();

        mockMvc.perform(get("/api/auth/sessions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        LocalDateTime segunda = lastSeenEnBase();

        // El throttle es lo que hace viable marcar en cada request: sin él,
        // navegar por la aplicación sería un UPDATE por click.
        assertThat(segunda)
                .as("dentro de la ventana de throttle no debe haber otro UPDATE")
                .isEqualTo(primera);
    }

    @Test
    void sinTokenNoSeMarcaNada() throws Exception {
        LocalDateTime antes = lastSeenEnBase();

        mockMvc.perform(get("/api/auth/sessions"))
                .andExpect(status().isUnauthorized());

        assertThat(lastSeenEnBase())
                .as("un request rechazado no es actividad de nadie")
                .isEqualTo(antes);
    }

    @Test
    void unTokenInvalidoTampocoMarca() throws Exception {
        LocalDateTime antes = lastSeenEnBase();

        mockMvc.perform(get("/api/auth/sessions").header("Authorization", "Bearer no-es-un-token"))
                .andExpect(status().isUnauthorized());

        assertThat(lastSeenEnBase()).isEqualTo(antes);
    }

    @Test
    void elUsuarioQuedaDentroDeLaVentanaDeEnLinea() throws Exception {
        String token = login();

        mockMvc.perform(get("/api/auth/sessions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // El frontend pinta "en línea" si last_seen tiene menos de 5 minutos.
        // Esta es la afirmación que le importa al usuario final: después de un
        // request, la persona figura conectada.
        Duration antiguedad = Duration.between(lastSeenEnBase(), LocalDateTime.now());
        assertThat(antiguedad)
                .as("tras actividad real, el indicador tiene que dar 'en línea'")
                .isLessThan(Duration.ofMinutes(5));
    }
}
