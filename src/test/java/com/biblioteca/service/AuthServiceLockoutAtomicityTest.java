package com.biblioteca.service;

import com.biblioteca.model.User;
import com.biblioteca.repository.UserRepository;
import com.biblioteca.security.AccessTokenDenylistService;
import com.biblioteca.security.EncryptionService;
import com.biblioteca.security.JwtTokenProvider;
import com.biblioteca.security.RefreshTokenService;
import com.biblioteca.security.TotpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Los contadores de bloqueo se llevan EN LA BASE, no en la entidad en memoria.
 *
 * El patrón viejo era "leer el contador de la entidad, sumarle uno, guardar".
 * Tiene dos problemas y los dos importan:
 *
 *  - La carrera: N intentos concurrentes leen el mismo valor y guardan el
 *    mismo resultado, así que cinco fallos simultáneos cuentan como uno y el
 *    bloqueo no llega nunca. Es justo el escenario que estos bloqueos existen
 *    para cubrir — el atacante distribuido al que el límite por IP no alcanza.
 *
 *  - La escritura de fila completa: save() sobre una entidad separada escribe
 *    TODAS las columnas con los valores que tenía al leerse, así que un login
 *    fallido concurrente con una edición de perfil podía revertir la edición.
 *
 * Estos tests fijan que el conteo y el bloqueo salgan como UPDATEs dirigidos.
 */
class AuthServiceLockoutAtomicityTest {

    private UserRepository userRepository;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.encode(any())).thenReturn("$2a$12$dummydummydummydummydum");

        authService = new AuthService(userRepository, passwordEncoder,
                mock(JwtTokenProvider.class), mock(TotpService.class),
                mock(RefreshTokenService.class), mock(EncryptionService.class),
                mock(AccessTokenDenylistService.class), mock(EmailCodeService.class),
                mock(LoginAlertService.class), mock(MailService.class), mock(EmailTemplates.class));
    }

    private static User user() {
        User u = new User();
        u.setId(7);
        u.setUsername("ana");
        return u;
    }

    @Test
    void elFalloDeContrasenaSeCuentaConUnUpdateDirigido() {
        User u = user();
        u.setFailedPasswordAttempts(2);

        authService.registerPasswordFailure(u, 5, Duration.ofMinutes(15));

        // Incremento relativo en la base: no se escribe "3", se escribe
        // "sumale uno a lo que haya". Dos peticiones concurrentes suman dos.
        verify(userRepository).incrementPasswordFailures(7);
        // Y jamás una escritura de fila completa, que arrastraría columnas
        // ajenas al contador.
        verify(userRepository, never()).save(any());
    }

    @Test
    void quienCruzaElUmbralLoDecideLaBase() {
        User u = user();
        u.setFailedPasswordAttempts(4);
        when(userRepository.lockPasswordIfExhausted(eq(7), eq(5), any())).thenReturn(1);

        authService.registerPasswordFailure(u, 5, Duration.ofMinutes(15));

        verify(userRepository).lockPasswordIfExhausted(eq(7), eq(5), any());
        assertThat(u.getPasswordLockedUntil()).isAfter(LocalDateTime.now().plusMinutes(14));
    }

    @Test
    void siLaBaseDiceQueNoSeCruzoElUmbralNoHayBloqueo() {
        User u = user();
        u.setFailedPasswordAttempts(1);
        when(userRepository.lockPasswordIfExhausted(anyInt(), anyInt(), any())).thenReturn(0);

        authService.registerPasswordFailure(u, 5, Duration.ofMinutes(15));

        // El contador en memoria pudo quedar viejo entre peticiones; manda el
        // valor real de la fila, no el que traía la entidad.
        assertThat(u.getPasswordLockedUntil()).isNull();
    }

    @Test
    void elUmbralDependeDeLaOperacion() {
        // Mismas columnas, techos distintos: login 10, contraseña actual 5.
        // La operación más estricta gana, que es la dirección segura del error.
        authService.registerPasswordFailure(user(), 10, Duration.ofMinutes(15));
        verify(userRepository).lockPasswordIfExhausted(eq(7), eq(10), any());
    }

    @Test
    void limpiarSoloEscribeSiHabiaEstadoQueLimpiar() {
        authService.clearPasswordFailures(user()); // contador en cero, sin bloqueo

        // El camino feliz del login no debe generar una escritura por request.
        verify(userRepository, never()).clearPasswordFailures(any());
    }

    @Test
    void limpiarBorraContadorYBloqueoDeUnaVez() {
        User u = user();
        u.setFailedPasswordAttempts(3);
        u.setPasswordLockedUntil(LocalDateTime.now().plusMinutes(5));

        authService.clearPasswordFailures(u);

        verify(userRepository).clearPasswordFailures(7);
        assertThat(u.getFailedPasswordAttempts()).isZero();
        assertThat(u.getPasswordLockedUntil()).isNull();
    }
}
