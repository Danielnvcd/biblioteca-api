package com.biblioteca.service;

import com.biblioteca.exception.ApiException;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
            validateMagicBytes(file, ext);

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

    // ─── Magic-byte validation ──────────────────────────────────────────────
    // Peek at the start of the upload and confirm it actually looks like the
    // declared extension. Stops a user from renaming evil.exe → evil.pdf and
    // tricking the rest of the app (or the people downloading it) into
    // running something unexpected.
    //
    // Each call to MultipartFile.getInputStream() returns a fresh stream, so
    // reading 32 bytes here doesn't consume the one used by Files.copy later.

    private static final int MAGIC_PEEK = 32;

    private void validateMagicBytes(MultipartFile file, String ext) throws IOException {
        byte[] head = new byte[MAGIC_PEEK];
        int read;
        try (InputStream in = file.getInputStream()) {
            read = in.readNBytes(head, 0, MAGIC_PEEK);
        }
        if (read <= 0) {
            throw ApiException.badRequest("Archivo vacío");
        }
        if (!matchesExpectedType(head, read, ext)) {
            throw ApiException.badRequest(
                    "El contenido del archivo no coincide con la extensión ." + ext);
        }
    }

    private static boolean matchesExpectedType(byte[] h, int len, String ext) {
        return switch (ext) {
            case "pdf"  -> startsWith(h, len, 0x25, 0x50, 0x44, 0x46);                  // %PDF
            case "png"  -> startsWith(h, len, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
            case "jpg", "jpeg" -> startsWith(h, len, 0xFF, 0xD8, 0xFF);
            case "gif"  -> startsWithStr(h, len, 0, "GIF87a") || startsWithStr(h, len, 0, "GIF89a");
            case "webp" -> startsWithStr(h, len, 0, "RIFF") && startsWithStr(h, len, 8, "WEBP");
            case "wav"  -> startsWithStr(h, len, 0, "RIFF") && startsWithStr(h, len, 8, "WAVE");
            case "ogg"  -> startsWithStr(h, len, 0, "OggS");
            case "webm" -> startsWith(h, len, 0x1A, 0x45, 0xDF, 0xA3);                  // EBML
            // MP3: ID3 tag header, or raw MPEG frame sync (FF Ex).
            case "mp3"  -> startsWithStr(h, len, 0, "ID3")
                    || (len >= 2 && h[0] == (byte) 0xFF && (h[1] & (byte) 0xE0) == (byte) 0xE0);
            // ISO BMFF family: bytes 4-7 must be "ftyp".
            case "mp4", "mov", "m4a" -> startsWithStr(h, len, 4, "ftyp");
            case "heic" -> startsWithStr(h, len, 4, "ftyp")
                    && (startsWithStr(h, len, 8, "heic")
                     || startsWithStr(h, len, 8, "heix")
                     || startsWithStr(h, len, 8, "mif1"));
            // OOXML containers (docx/xlsx/pptx/xlsm) are ZIP files.
            case "docx", "xlsx", "xlsm", "pptx" ->
                    startsWith(h, len, 0x50, 0x4B, 0x03, 0x04)
                 || startsWith(h, len, 0x50, 0x4B, 0x05, 0x06)
                 || startsWith(h, len, 0x50, 0x4B, 0x07, 0x08);
            // Legacy Office (.doc / .xls / .ppt) use the OLE/CFB header.
            case "doc", "xls", "ppt" ->
                    startsWith(h, len, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1);
            // Unknown extensions were rejected earlier by the allow-list; if a
            // new one is whitelisted without a signature, fail-open here rather
            // than blocking it silently.
            default -> true;
        };
    }

    /** True if the first {@code expected.length} bytes of {@code h} match the given ints (treated as unsigned bytes). */
    private static boolean startsWith(byte[] h, int len, int... expected) {
        if (len < expected.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if (h[i] != (byte) expected[i]) return false;
        }
        return true;
    }

    private static boolean startsWithStr(byte[] h, int len, int offset, String s) {
        byte[] needle = s.getBytes(StandardCharsets.US_ASCII);
        if (len < offset + needle.length) return false;
        for (int i = 0; i < needle.length; i++) {
            if (h[offset + i] != needle[i]) return false;
        }
        return true;
    }
}
