package com.biblioteca.config;

import com.biblioteca.model.Category;
import com.biblioteca.model.User;
import com.biblioteca.repository.CategoryRepository;
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
    private final CategoryRepository categoryRepository;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(UserRepository userRepository, CategoryRepository categoryRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.categoryRepository = categoryRepository;
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

        seedCategories();
    }

    private void seedCategories() {
        if (categoryRepository.count() == 0) {
            log.info("⏳ Inicializando categorías por defecto...");
            
            // Categorías de Almacenes
            String[] almacenes = {"Información general", "Avisos Importantes", "Doc. de operación", "Instructivos", "Estadísticas semanales", "Méritos y resultados", "MSDS", "Lectura"};
            for (int i = 0; i < almacenes.length; i++) {
                categoryRepository.save(new Category(almacenes[i], "almacenes", i));
            }

            // Categorías de Manuales
            String[] manuales = {"Almacén", "Ventas", "Producción INY", "Producción", "Compras", "Producción PEAD", "Administrativos", "Logística", "General", "Aseguramiento de calidad", "Producción PET"};
            for (int i = 0; i < manuales.length; i++) {
                categoryRepository.save(new Category(manuales[i], "manuales", i));
            }

            // Categorías de Documentos
            String[] documentos = {"General", "Administrativos", "Almacén", "Logística", "Aseguramiento de calidad", "Compras", "Producción", "Ventas", "Producción INY", "Producción PET", "Producción PEAD"};
            for (int i = 0; i < documentos.length; i++) {
                categoryRepository.save(new Category(documentos[i], "documentos", i));
            }

            log.info("✅ Categorías inicializadas.");
        }
    }
}
