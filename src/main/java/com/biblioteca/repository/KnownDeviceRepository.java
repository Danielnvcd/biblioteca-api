package com.biblioteca.repository;

import com.biblioteca.model.KnownDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

public interface KnownDeviceRepository extends JpaRepository<KnownDevice, Long> {

    Optional<KnownDevice> findByUserIdAndDeviceHash(Integer userId, String deviceHash);

    boolean existsByUserId(Integer userId);

    /**
     * UPDATE dirigido en vez de save() de la entidad completa: esto corre en
     * cada inicio de sesión y lo único que cambia son dos columnas.
     */
    @Modifying
    @Transactional
    @Query("UPDATE KnownDevice d SET d.lastSeen = :now, d.lastIp = :ip WHERE d.id = :id")
    int touch(@Param("id") Long id, @Param("now") LocalDateTime now, @Param("ip") String ip);
}
