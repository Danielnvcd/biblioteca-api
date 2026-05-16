package com.biblioteca.config;

import com.biblioteca.model.User;
import com.biblioteca.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        String adminUsername = "admin";

        if (!userRepository.existsByUsername(adminUsername)) {
            User admin = new User();
            admin.setUsername(adminUsername);
            admin.setPasswordHash(passwordEncoder.encode("Admin1234!"));
            admin.setRole("super_admin");
            admin.setFullName("Administrador");
            userRepository.save(admin);
            log.info("✅ Usuario super admin creado: username='admin', password='Admin1234!'");
        } else {
            log.info("ℹ️  El usuario 'admin' ya existe, no se creó de nuevo.");
        }
    }
}
