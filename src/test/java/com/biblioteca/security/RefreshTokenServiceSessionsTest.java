package com.biblioteca.security;

import com.biblioteca.model.RefreshToken;
import com.biblioteca.repository.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cubre lo que alimenta la pantalla "sesiones activas" del perfil.
 *
 * El riesgo concreto que vigilan estos tests es que "cerrar las demás sesiones"
 * cierre también la propia: quien aprieta el botón quedaría deslogueado, que es
 * exactamente lo contrario de lo que el botón promete.
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceSessionsTest {

    @Mock private RefreshTokenRepository repo;
    @Mock private PlatformTransactionManager txManager;

    private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        service = new RefreshTokenService(repo, txManager, 604_800_000L);
    }

    private static RefreshToken token(long id, String hash) {
        RefreshToken rt = new RefreshToken();
        rt.setId(id);
        rt.setUserId(42);
        rt.setTokenHash(hash);
        rt.setCreatedAt(LocalDateTime.now());
        return rt;
    }

    @Test
    void activeSessionsPideSoloLasVigentes() {
        when(repo.findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(eq(42), any()))
                .thenReturn(List.of(token(1, "h1"), token(2, "h2")));

        assertThat(service.activeSessions(42)).hasSize(2);

        // El filtro de vencidas se hace en la query, no en memoria: se verifica
        // que el corte temporal que se le pasa sea "ahora".
        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repo).findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(42), cutoff.capture());
        assertThat(cutoff.getValue()).isCloseTo(LocalDateTime.now(),
                within(5, java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    void sessionIdOfResuelveElTokenCrudoSinExponerElHash() {
        // El servicio hashea internamente; el caller solo tiene el valor crudo
        // de la cookie. Se emite uno real para no acoplar el test al algoritmo.
        when(repo.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));
        String raw = service.issue(42, "10.0.0.1", "JUnit");

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repo).save(captor.capture());
        String hashGuardado = captor.getValue().getTokenHash();

        when(repo.findByTokenHash(hashGuardado)).thenReturn(Optional.of(token(99, hashGuardado)));

        assertThat(service.sessionIdOf(raw)).isEqualTo(99L);
    }

    @Test
    void sessionIdOfDevuelveNullSinCookie() {
        assertThat(service.sessionIdOf(null)).isNull();
        assertThat(service.sessionIdOf("   ")).isNull();
        verify(repo, never()).findByTokenHash(any());
    }

    @Test
    void revokeOthersConservaLaSesionActual() {
        when(repo.revokeAllActiveForUserExcept(eq(42), eq(99L), any())).thenReturn(3);

        assertThat(service.revokeOthers(42, 99L)).isEqualTo(3);

        verify(repo).revokeAllActiveForUserExcept(eq(42), eq(99L), any());
        // La variante sin excepción cerraría también la sesión de quien pidió
        // la operación: no debe usarse cuando hay una sesión identificada.
        verify(repo, never()).revokeAllActiveForUser(anyInt(), any());
    }

    @Test
    void sinSesionIdentificadaCierraTodas() {
        // Sin cookie no hay forma de saber cuál conservar. Cerrar todas es la
        // opción segura; el controller avisa al frontend con keptCurrent=false.
        when(repo.revokeAllActiveForUser(eq(42), any())).thenReturn(4);

        assertThat(service.revokeOthers(42, null)).isEqualTo(4);

        verify(repo).revokeAllActiveForUser(eq(42), any());
        verify(repo, never()).revokeAllActiveForUserExcept(anyInt(), anyLong(), any());
    }

    private static org.assertj.core.data.TemporalUnitOffset within(long value, java.time.temporal.TemporalUnit unit) {
        return new org.assertj.core.data.TemporalUnitWithinOffset(value, unit);
    }
}
