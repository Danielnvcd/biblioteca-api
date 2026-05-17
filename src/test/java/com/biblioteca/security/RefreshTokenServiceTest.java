package com.biblioteca.security;

import com.biblioteca.exception.ApiException;
import com.biblioteca.model.RefreshToken;
import com.biblioteca.repository.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock private RefreshTokenRepository repo;
    @Mock private PlatformTransactionManager txManager;

    private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        // El TransactionTemplate llama getTransaction al ejecutar el revoke
        // family-wide. Solo algunos tests lo invocan — lenient() evita que
        // strict mode falle en los que no.
        lenient().when(txManager.getTransaction(any()))
                .thenReturn(new SimpleTransactionStatus());
        service = new RefreshTokenService(repo, txManager, 604_800_000L);  // 7 días
    }

    @Test
    void issue_savesTokenWithHashAndReturnsRawValue() {
        when(repo.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));

        String raw = service.issue(42, "10.0.0.1", "JUnit");

        assertThat(raw).isNotBlank();
        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repo).save(captor.capture());
        RefreshToken saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(42);
        assertThat(saved.getTokenHash()).hasSize(64);            // SHA-256 hex
        assertThat(saved.getTokenHash()).isNotEqualTo(raw);      // hash, no plaintext
        assertThat(saved.getTokenHash()).isEqualTo(sha256(raw)); // hash correcto
        assertThat(saved.getRevokedAt()).isNull();
        assertThat(saved.getIp()).isEqualTo("10.0.0.1");
    }

    @Test
    void rotate_validToken_revokesOldAndIssuesNew() {
        String raw = "raw-token-abc";
        RefreshToken existing = makeToken(100L, 1, sha256(raw));
        when(repo.findByTokenHash(sha256(raw))).thenReturn(Optional.of(existing));

        AtomicLong successorId = new AtomicLong(101);
        when(repo.save(any(RefreshToken.class))).thenAnswer(inv -> {
            RefreshToken rt = inv.getArgument(0);
            if (rt.getId() == null) rt.setId(successorId.getAndIncrement());
            return rt;
        });
        when(repo.markRevokedIfActive(eq(100L), any(LocalDateTime.class), eq(101L)))
                .thenReturn(1);

        RefreshTokenService.Rotated result = service.rotate(raw, "ip", "ua");

        assertThat(result.userId()).isEqualTo(1);
        assertThat(result.rawToken()).isNotEqualTo(raw);
        verify(repo).markRevokedIfActive(eq(100L), any(LocalDateTime.class), eq(101L));
        verify(repo, never()).delete(any());
    }

    @Test
    void rotate_alreadyRevokedToken_triggersReuseDetection() {
        RefreshToken revoked = makeToken(7L, 1, "anyHash");
        revoked.setRevokedAt(LocalDateTime.now().minusMinutes(1));
        when(repo.findByTokenHash(any())).thenReturn(Optional.of(revoked));

        assertThatThrownBy(() -> service.rotate("anything", "ip", "ua"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Sesión inválida");

        verify(repo).revokeAllActiveForUser(eq(1), any(LocalDateTime.class));
        verify(repo, never()).markRevokedIfActive(anyLong(), any(), any());
    }

    @Test
    void rotate_expiredToken_throws() {
        RefreshToken expired = makeToken(8L, 1, "anyHash");
        expired.setExpiresAt(LocalDateTime.now().minusDays(1));
        when(repo.findByTokenHash(any())).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.rotate("anything", "ip", "ua"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Sesión expirada");
    }

    @Test
    void rotate_unknownHash_throws() {
        when(repo.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rotate("anything", "ip", "ua"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Sesión expirada");
    }

    @Test
    void rotate_lostRace_deletesOrphanAndRevokesFamily() {
        // Caso central del fix #2: dos requests con el mismo token llegan a la
        // vez. markRevokedIfActive devuelve 0 (otro ganó), el caller borra su
        // sucesor huérfano y revoca la familia.
        String raw = "raw-token-x";
        RefreshToken existing = makeToken(50L, 9, sha256(raw));
        when(repo.findByTokenHash(sha256(raw))).thenReturn(Optional.of(existing));

        AtomicLong nextId = new AtomicLong(51);
        when(repo.save(any(RefreshToken.class))).thenAnswer(inv -> {
            RefreshToken rt = inv.getArgument(0);
            if (rt.getId() == null) rt.setId(nextId.getAndIncrement());
            return rt;
        });
        when(repo.markRevokedIfActive(eq(50L), any(LocalDateTime.class), any()))
                .thenReturn(0);  // perdimos la carrera

        assertThatThrownBy(() -> service.rotate(raw, "ip", "ua"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Sesión inválida");

        ArgumentCaptor<RefreshToken> deleted = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repo).delete(deleted.capture());
        assertThat(deleted.getValue().getId()).isEqualTo(51L);  // el sucesor huérfano
        verify(repo).revokeAllActiveForUser(eq(9), any(LocalDateTime.class));
    }

    @Test
    void revoke_nullOrBlank_isNoOp() {
        service.revoke(null);
        service.revoke("");
        service.revoke("   ");

        verify(repo, never()).findByTokenHash(any());
        verify(repo, never()).save(any());
    }

    @Test
    void revoke_activeToken_setsRevokedAt() {
        RefreshToken token = makeToken(60L, 3, "anyHash");
        when(repo.findByTokenHash(any())).thenReturn(Optional.of(token));

        service.revoke("anything");

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getRevokedAt()).isNotNull();
    }

    @Test
    void revoke_alreadyRevokedToken_doesNotSaveAgain() {
        RefreshToken already = makeToken(70L, 3, "anyHash");
        already.setRevokedAt(LocalDateTime.now().minusHours(1));
        when(repo.findByTokenHash(any())).thenReturn(Optional.of(already));

        service.revoke("anything");

        verify(repo, never()).save(any());
    }

    // ---------- helpers ----------

    private static RefreshToken makeToken(Long id, Integer userId, String hash) {
        RefreshToken rt = new RefreshToken();
        rt.setId(id);
        rt.setUserId(userId);
        rt.setTokenHash(hash);
        rt.setCreatedAt(LocalDateTime.now().minusMinutes(10));
        rt.setExpiresAt(LocalDateTime.now().plusDays(7));
        return rt;
    }

    /** Replica el hash interno del servicio para alinear lookups en mocks. */
    private static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
