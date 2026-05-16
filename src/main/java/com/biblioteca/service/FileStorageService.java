package com.biblioteca.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class FileStorageService {

    @Value("${app.upload.dir}")
    private String uploadDir;

    private Path uploadRoot;

    private static final Set<String> ALLOWED_CATEGORIES = Set.of(
            "documentos", "manuales", "cursos", "boletin", "lecciones",
            "almacenes", "quejas", "seguridad", "perfiles");
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    @PostConstruct
    public void init() {
        uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(uploadRoot);
            for (String dir : ALLOWED_CATEGORIES) {
                Files.createDirectories(uploadRoot.resolve(dir));
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not create upload directories", e);
        }
    }

    public String store(MultipartFile file, String category) {
        Path targetDir = resolveCategoryDir(category);
        try {
            String original = file.getOriginalFilename();
            String safeOriginal = sanitizeFilename(original);
            String timestamp = LocalDateTime.now().format(DTF);
            String newName = timestamp + "_" + safeOriginal;

            Files.createDirectories(targetDir);
            Path targetPath = targetDir.resolve(newName).normalize();
            if (!targetPath.startsWith(targetDir)) {
                throw new IllegalArgumentException("Ruta de archivo inválida");
            }
            Files.copy(file.getInputStream(), targetPath);
            return newName;
        } catch (IOException e) {
            throw new RuntimeException("Failed to store file", e);
        }
    }

    public void delete(String filename, String category) {
        try {
            Path path = resolvePath(category, filename);
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
        }
    }

    public Path getPath(String category, String filename) {
        return resolvePath(category, filename);
    }

    private Path resolveCategoryDir(String category) {
        String normalized = category == null ? "" : category.toLowerCase(Locale.ROOT);
        if (!ALLOWED_CATEGORIES.contains(normalized)) {
            throw new IllegalArgumentException("Categoría no permitida: " + category);
        }
        Path dir = uploadRoot.resolve(normalized).normalize();
        if (!dir.startsWith(uploadRoot)) {
            throw new IllegalArgumentException("Categoría inválida");
        }
        return dir;
    }

    private Path resolvePath(String category, String filename) {
        Path dir = resolveCategoryDir(category);
        String safe = sanitizeFilename(filename);
        Path resolved = dir.resolve(safe).normalize();
        if (!resolved.startsWith(dir)) {
            throw new IllegalArgumentException("Nombre de archivo inválido");
        }
        return resolved;
    }

    private String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String base = Paths.get(name).getFileName().toString();
        return base.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}
