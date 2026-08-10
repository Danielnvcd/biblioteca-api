package com.biblioteca.repository;

import com.biblioteca.model.User;
import com.biblioteca.security.UserSecurityState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Integer> {
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);

    /**
     * El correo se guarda siempre normalizado a minúsculas (ver
     * {@code EmailAddresses.normalize}), así que la comparación exacta alcanza
     * y aprovecha el índice único parcial de V9.
     */
    Optional<User> findByEmail(String email);

    /**
     * ¿Hay OTRA cuenta apuntando a esta misma foto?
     *
     * Se consulta antes de borrar un avatar del disco. Los nombres que genera
     * hoy FileStorageService llevan timestamp + UUID, así que no deberían
     * repetirse — pero quedan archivos heredados de la app anterior con nombres
     * previsibles ({@code user_1_foto.jpg}), y borrar por las malas la foto de
     * otra persona es de esos errores que nadie relaciona con la causa.
     */
    boolean existsByProfilePicAndIdNot(String profilePic, Integer id);
    @Query("SELECT new com.biblioteca.security.UserSecurityState(" +
           "u.id, u.username, u.role, u.passwordChangedAt, u.lastSeen) " +
           "FROM User u WHERE u.id = :id")
    Optional<UserSecurityState> findSecurityStateById(@Param("id") Integer id);

    /**
     * Cheap UPDATE that touches only last_seen — avoids full-row writes and
     * row-level lock contention from concurrent /auth/me calls.
     */
    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.lastSeen = :ts WHERE u.id = :id")
    int touchLastSeen(@Param("id") Integer id, @Param("ts") LocalDateTime ts);

    /*
     * ========================================================================
     * CONTADORES DE BLOQUEO — por qué son UPDATEs y no save() de la entidad
     * ========================================================================
     *
     * Dos motivos, y los dos importan:
     *
     * 1) La carrera. Con "leer el contador de la entidad → sumarle uno →
     *    guardar", N peticiones concurrentes leen el mismo valor y escriben el
     *    mismo resultado: cinco fallos simultáneos cuentan como uno y el
     *    bloqueo no llega nunca. Es exactamente el escenario que estos
     *    bloqueos existen para cubrir — el atacante distribuido al que el
     *    límite por IP no alcanza —, así que dejarlo así era tener la defensa
     *    escrita pero no puesta.
     *
     * 2) La escritura de fila completa. save() sobre una entidad separada
     *    escribe TODAS las columnas con los valores que tenía cuando se leyó.
     *    Un login fallido concurrente con una edición de perfil podía revertir
     *    la edición sin que nadie lo notara. Estos UPDATEs tocan solo las
     *    columnas del contador.
     *
     * El umbral viaja como parámetro porque las mismas columnas las comparten
     * operaciones con techos distintos (login 10, /change-password 5), y quién
     * cruzó el umbral tiene que decidirlo la base mirando el valor real.
     */

    /** Suma un fallo de código por correo. */
    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.failedEmailCodeAttempts = u.failedEmailCodeAttempts + 1 "
         + "WHERE u.id = :id")
    int incrementEmailCodeFailures(@Param("id") Integer id);

    /**
     * Aplica el bloqueo si el contador ya llegó al umbral. Va como UPDATE
     * condicional, por el mismo motivo que el anterior: quién cruzó el umbral
     * lo decide la base mirando el valor real, no un cálculo hecho sobre una
     * lectura que pudo quedar vieja.
     *
     * No pisa un bloqueo vigente más largo — reintentar durante el castigo no
     * debe poder acortarlo.
     */
    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.emailCodeLockedUntil = :until WHERE u.id = :id "
         + "AND u.failedEmailCodeAttempts >= :threshold "
         + "AND (u.emailCodeLockedUntil IS NULL OR u.emailCodeLockedUntil < :until)")
    int lockEmailCodesIfExhausted(@Param("id") Integer id,
                                  @Param("threshold") int threshold,
                                  @Param("until") LocalDateTime until);

    /** Limpia el estado de fallos tras un código correcto. */
    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.failedEmailCodeAttempts = 0, u.emailCodeLockedUntil = NULL "
         + "WHERE u.id = :id")
    int clearEmailCodeFailures(@Param("id") Integer id);

    // ---- Contraseña (login y verificación de contraseña actual) ----

    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.failedPasswordAttempts = u.failedPasswordAttempts + 1 "
         + "WHERE u.id = :id")
    int incrementPasswordFailures(@Param("id") Integer id);

    /** No acorta un bloqueo vigente más largo: reintentar durante el castigo no debe aliviarlo. */
    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.passwordLockedUntil = :until WHERE u.id = :id "
         + "AND u.failedPasswordAttempts >= :threshold "
         + "AND (u.passwordLockedUntil IS NULL OR u.passwordLockedUntil < :until)")
    int lockPasswordIfExhausted(@Param("id") Integer id,
                                @Param("threshold") int threshold,
                                @Param("until") LocalDateTime until);

    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.failedPasswordAttempts = 0, u.passwordLockedUntil = NULL "
         + "WHERE u.id = :id")
    int clearPasswordFailures(@Param("id") Integer id);

    // ---- TOTP ----

    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.failedTotpAttempts = u.failedTotpAttempts + 1 WHERE u.id = :id")
    int incrementTotpFailures(@Param("id") Integer id);

    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.totpLockedUntil = :until WHERE u.id = :id "
         + "AND u.failedTotpAttempts >= :threshold "
         + "AND (u.totpLockedUntil IS NULL OR u.totpLockedUntil < :until)")
    int lockTotpIfExhausted(@Param("id") Integer id,
                            @Param("threshold") int threshold,
                            @Param("until") LocalDateTime until);

    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.failedTotpAttempts = 0, u.totpLockedUntil = NULL WHERE u.id = :id")
    int clearTotpFailures(@Param("id") Integer id);
}
