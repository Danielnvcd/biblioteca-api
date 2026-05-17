package com.biblioteca.config;

import com.biblioteca.model.User;
import com.biblioteca.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    /** Password for the initial admin. Read from env so prod doesn't ship a known value. */
    private final String adminInitialPassword;

    public DataInitializer(UserRepository userRepository,
                           PasswordEncoder passwordEncoder,
                           @Value("${app.admin.initial-password:}") String adminInitialPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminInitialPassword = adminInitialPassword;
    }

    @Override
    public void run(String... args) {
        String adminUsername = "admin";

        if (userRepository.existsByUsername(adminUsername)) {
            log.info("ℹ️  El usuario 'admin' ya existe, no se creó de nuevo.");
            return;
        }

        if (adminInitialPassword == null || adminInitialPassword.isBlank()) {
            // Nothing to do — operator must create the admin via a one-off script or
            // by setting ADMIN_INITIAL_PASSWORD on first boot. Better to refuse to
            // bootstrap a default credential than to silently ship one.
            log.warn("⚠️  No existe usuario 'admin' y ADMIN_INITIAL_PASSWORD no está definido. "
                    + "Define la variable y reinicia para crearlo.");
            return;
        }

        User admin = new User();
        admin.setUsername(adminUsername);
        admin.setPasswordHash(passwordEncoder.encode(adminInitialPassword));
        admin.setRole("super_admin");
        admin.setFullName("Administrador");
        userRepository.save(admin);
        log.info("✅ Usuario super admin creado: username='admin' (password tomado de ADMIN_INITIAL_PASSWORD).");
    }
}
