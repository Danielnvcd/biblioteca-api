package com.biblioteca.repository;

import com.biblioteca.model.EmailCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

public interface EmailCodeRepository extends JpaRepository<EmailCode, Long> {

    /**
     * El único código vivo de ese usuario para ese propósito. Debería haber a
     * lo sumo uno (la emisión quema los anteriores), pero se ordena por fecha
     * y se toma el primero para que una carrera entre dos emisiones no deje el
     * flujo colgado con una excepción de "non-unique result".
     */
    @Query("SELECT c FROM EmailCode c WHERE c.userId = :userId AND c.purpose = :purpose "
         + "AND c.consumedAt IS NULL AND c.expiresAt > :now ORDER BY c.createdAt DESC LIMIT 1")
    Optional<EmailCode> findLive(@Param("userId") Integer userId,
                                 @Param("purpose") String purpose,
                                 @Param("now") LocalDateTime now);

    /**
     * Consumo atómico. La condición {@code consumedAt IS NULL} viaja en el
     * WHERE a propósito: dos requests concurrentes con el mismo código válido
     * compiten por la misma fila y solo una recibe 1: la otra ve 0 y trata el
     * código como inválido. Con un save() común las dos habrían pasado.
     */
    @Modifying
    @Transactional
    @Query("UPDATE EmailCode c SET c.consumedAt = :now WHERE c.id = :id AND c.consumedAt IS NULL")
    int consume(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * Reserva un intento contra el código, o devuelve 0 si ya no quedan.
     *
     * El tope viaja en el WHERE y no en un {@code if} previo de Java a
     * propósito. Con "leer attempts → decidir → incrementar", N requests
     * concurrentes leen todas el mismo valor, todas se creen por debajo del
     * tope y todas llegan a probar su código: el límite de 5 intentos deja de
     * existir justo contra el atacante que sabe paralelizar. Aquí la base
     * arbitra, y como mucho se conceden MAX reservas en total.
     *
     * Se reserva ANTES de comparar el hash, así que un intento cuenta aunque
     * la request se corte a la mitad.
     */
    @Modifying
    @Transactional
    @Query("UPDATE EmailCode c SET c.attempts = c.attempts + 1 "
         + "WHERE c.id = :id AND c.consumedAt IS NULL AND c.attempts < :max")
    int reserveAttempt(@Param("id") Long id, @Param("max") short max);

    /** Quema el código si ya agotó sus intentos. Atómico e idempotente. */
    @Modifying
    @Transactional
    @Query("UPDATE EmailCode c SET c.consumedAt = :now "
         + "WHERE c.id = :id AND c.consumedAt IS NULL AND c.attempts >= :max")
    int burnIfExhausted(@Param("id") Long id, @Param("max") short max, @Param("now") LocalDateTime now);

    /**
     * Quema todos los códigos vivos del usuario para ese propósito. Se llama
     * al emitir uno nuevo: si quedaran varios vivos a la vez, la probabilidad
     * de acertar uno al azar se multiplicaría por la cantidad de códigos.
     */
    @Modifying
    @Transactional
    @Query("UPDATE EmailCode c SET c.consumedAt = :now WHERE c.userId = :userId "
         + "AND c.purpose = :purpose AND c.consumedAt IS NULL")
    int invalidateLive(@Param("userId") Integer userId,
                       @Param("purpose") String purpose,
                       @Param("now") LocalDateTime now);

    /** Cuántos códigos se emitieron desde `since` — techo de emisión por ventana. */
    @Query("SELECT COUNT(c) FROM EmailCode c WHERE c.userId = :userId AND c.purpose = :purpose "
         + "AND c.createdAt >= :since")
    long countIssuedSince(@Param("userId") Integer userId,
                          @Param("purpose") String purpose,
                          @Param("since") LocalDateTime since);

    /** Fecha del último código emitido — sostiene el cooldown entre reenvíos. */
    @Query("SELECT MAX(c.createdAt) FROM EmailCode c WHERE c.userId = :userId AND c.purpose = :purpose")
    Optional<LocalDateTime> lastIssuedAt(@Param("userId") Integer userId,
                                         @Param("purpose") String purpose);

    /**
     * Borra los vencidos hace rato. Sin esto la tabla crece sin techo: cada
     * intento de login con 2FA por correo deja una fila que ya no sirve para
     * nada apenas expira.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM EmailCode c WHERE c.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") LocalDateTime cutoff);
}
