package com.biblioteca.service;

import com.biblioteca.exception.ApiException;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class FileStorageService {

    @Value("${app.upload.dir}")
    private String uploadDir;

    @Value("${app.allowed-extensions}")
    private String allowedExtensionsCsv;

    private Path uploadRoot;
    private Set<String> allowedExtensions;

    private static final Set<String> ALLOWED_CATEGORIES = Set.of(
            "documentos", "manuales", "cursos", "boletin", "lecciones",
            "almacenes", "quejas", "seguridad", "perfiles");
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    @PostConstruct
    public void init() {
        uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
        allowedExtensions = Arrays.stream(allowedExtensionsCsv.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(HashSet::new));
        // Always tolerate common image formats used by profile pics + previews
        allowedExtensions.addAll(Arrays.asList("jpeg", "gif", "webp", "m4a", "ogg", "webm", "mov"));
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
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Archivo vacío");
        }
        Path targetDir = resolveCategoryDir(category);
        try {
            String original = file.getOriginalFilename();
            String safeOriginal = sanitizeFilename(original);
            String ext = extensionOf(safeOriginal);
            if (ext.isEmpty() || !allowedExtensions.contains(ext)) {
                throw ApiException.badRequest("Extensión no permitida: ." + ext);
            }

            // Random suffix to avoid name collisions even within the same second.
            String timestamp = LocalDateTime.now().format(DTF);
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            String newName = timestamp + "_" + suffix + "_" + safeOriginal;

            Files.createDirectories(targetDir);
            Path targetPath = targetDir.resolve(newName).normalize();
            if (!targetPath.startsWith(targetDir)) {
                throw new IllegalArgumentException("Ruta de archivo inválida");
            }
            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);
            return newName;
        } catch (IOException e) {
            throw new RuntimeException("Failed to store file", e);
        }
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot < name.length() - 1
                ? name.substring(dot + 1).toLowerCase(Locale.ROOT)
                : "";
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
