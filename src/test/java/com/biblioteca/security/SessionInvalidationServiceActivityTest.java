package com.biblioteca.security;

import com.biblioteca.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Cubre la marca de actividad que alimenta el indicador "en línea".
 *
 * El bug que originó esto: last_seen solo se tocaba en /auth/me, que el
 * frontend llama una vez al arrancar. El campo terminaba registrando "cuándo
 * recargó la página" en vez de "cuándo estuvo activo", así que cualquiera que
 * trabajara sin apretar F5 figuraba desconectado a los cinco minutos.
 */
@ExtendWith(MockitoExtension.class)
class SessionInvalidationServiceActivityTest {

    @Mock private UserRepository userRepository;

    private SessionInvalidationService service;

    @BeforeEach
    void setUp() {
        service = new SessionInvalidationService(userRepository);
    }

    private static UserSecurityState estado(LocalDateTime lastSeen) {
        return new UserSecurityState(7, "ana", "user", null, lastSeen);
    }

    @Test
    void sinMarcaPreviaRegistraActividad() {
        service.markActive(estado(null));

        ArgumentCaptor<LocalDateTime> ts = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(userRepository).touchLastSeen(eq(7), ts.capture());
        assertThat(ts.getValue()).isBetween(LocalDateTime.now().minusSeconds(5), LocalDateTime.now());
    }

    @Test
    void marcaViejaSeRefresca() {
        service.markActive(estado(LocalDateTime.now().minusMinutes(10)));

        verify(userRepository).touchLastSeen(eq(7), any());
    }

    @Test
    void marcaRecienteNoEscribe() {
        // El throttle es lo que hace barato marcar en CADA request: sin esto,
        // cada navegación del usuario sería un UPDATE.
        service.markActive(estado(LocalDateTime.now().minusSeconds(30)));

        verify(userRepository, never()).touchLastSeen(anyInt(), any());
    }

    @Test
    void justoEnElLimiteNoEscribe() {
        // Un minuto y medio sigue dentro de la ventana de dos minutos.
        service.markActive(estado(LocalDateTime.now().minusSeconds(90)));

        verify(userRepository, never()).touchLastSeen(anyInt(), any());
    }

    @Test
    void pasadaLaVentanaVuelveAEscribir() {
        service.markActive(estado(LocalDateTime.now().minusMinutes(2).minusSeconds(1)));

        verify(userRepository).touchLastSeen(eq(7), any());
    }

    @Test
    void estadoNuloNoRompeNada() {
        assertThatCode(() -> service.markActive(null)).doesNotThrowAnyException();
        verify(userRepository, never()).touchLastSeen(anyInt(), any());
    }

    @Test
    void unFalloDeBaseNoTumbaElRequest() {
        // Corre dentro del filtro de autenticación: si el UPDATE explota, el
        // usuario tiene que poder seguir usando la app igual. Un puntito verde
        // no justifica devolver un 500.
        doThrow(new RuntimeException("BD caída"))
                .when(userRepository).touchLastSeen(anyInt(), any());

        assertThatCode(() -> service.markActive(estado(null))).doesNotThrowAnyException();
    }
}
