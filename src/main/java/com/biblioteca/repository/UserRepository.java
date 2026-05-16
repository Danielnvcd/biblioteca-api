package com.biblioteca.repository;

import com.biblioteca.model.User;
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
     * Cheap UPDATE that touches only last_seen — avoids full-row writes and
     * row-level lock contention from concurrent /auth/me calls.
     */
    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.lastSeen = :ts WHERE u.id = :id")
    int touchLastSeen(@Param("id") Integer id, @Param("ts") LocalDateTime ts);
}
