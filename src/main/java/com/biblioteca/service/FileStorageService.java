package com.biblioteca.service;

import com.biblioteca.exception.ApiException;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;


import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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

    /**
     * Restricciones de extensión MÁS estrictas que la allow-list global, por
     * categoría. Se intersecan con {@code app.allowed-extensions} — nunca la
     * amplían.
     *
     * `perfiles` es el caso importante: está en PUBLIC_CATEGORIES de
     * FileController (se sirve SIN autenticación para que el <img src> del
     * frontend funcione), y cualquier usuario logueado puede subir ahí vía
     * POST /api/auth/profile. Con la allow-list global eso permitía publicar
     * un PDF/DOCX/MP4 de hasta 50 MB en una URL pública del dominio de la
     * empresa — hosting gratis para phishing y abuso de disco. Una foto de
     * perfil solo necesita ser una imagen.
     *
     * Las demás categorías públicas (boletin, seguridad) SÍ alojan documentos
     * y audio de forma legítima (ver SeguridadController: podcasts .mp3,
     * manuales .pdf), así que no se restringen acá.
     *
     * Solo afecta subidas nuevas: los archivos ya almacenados se siguen
     * sirviendo con normalidad.
     */
    private static final Map<String, Set<String>> CATEGORY_EXTENSIONS = Map.of(
            "perfiles", Set.of("jpg", "jpeg", "png", "webp", "gif"));
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
            Set<String> categoryLimit = CATEGORY_EXTENSIONS.get(
                    category == null ? "" : category.toLowerCase(Locale.ROOT));
            if (categoryLimit != null && !categoryLimit.contains(ext)) {
                throw ApiException.badRequest("Extensión no permitida en esta sección: ." + ext);
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

    private static final int THUMB_WIDTH = 400;

    // Lock por path para generar thumbs sin que dos requests se pisen al escribir.
    // Antes usábamos String.intern() — funcional pero crece el string pool del
    // JVM sin liberar. Ahora un ConcurrentHashMap acotado por # de archivos
    // reales (no por input del cliente: getThumbnailPath solo crea entrada
    // cuando el original existe en disco).
    private final ConcurrentHashMap<Path, Object> thumbLocks = new ConcurrentHashMap<>();

    /**
     * Devuelve el path del thumbnail (400px de ancho, JPG). Lo genera la
     * primera vez que se pide; las siguientes lo lee del disco.
     *
     * - Sync por path interno para que dos requests concurrentes sobre el
     *   mismo archivo no se pisen al escribir (antes generaban .thumb.jpg
     *   corruptos).
     * - Escribe a *.tmp y mueve atómico, así un lector concurrente nunca ve
     *   un archivo a medio escribir.
     * - Downscale multi-step con Graphics2D + BILINEAR. Sustituye al viejo
     *   getScaledInstance(SCALE_SMOOTH), que era ~10× más lento y de peor
     *   calidad (era el origen del lag al cargar grids con muchas imágenes).
     */
    public Path getThumbnailPath(String category, String filename) {
        Path originalPath = resolvePath(category, filename);
        if (!Files.exists(originalPath)) {
            return originalPath; // Let it 404 naturally
        }

        String ext = extensionOf(filename).toLowerCase(Locale.ROOT);
        if (!Arrays.asList("jpg", "jpeg", "png", "gif", "bmp").contains(ext)) {
            return originalPath; // ImageIO no soporta WebP/HEIC sin plugins
        }

        Path thumbPath = originalPath.getParent().resolve(originalPath.getFileName().toString() + ".thumb.jpg");
        if (Files.exists(thumbPath)) {
            return thumbPath;
        }

        Object lock = thumbLocks.computeIfAbsent(thumbPath, k -> new Object());
        synchronized (lock) {
            // Re-check: otro hilo pudo haberlo generado mientras esperábamos el lock.
            if (Files.exists(thumbPath)) {
                return thumbPath;
            }
            try {
                if (exceedsPixelBudget(originalPath)) {
                    // No se genera thumb: se sirve el original. Peor experiencia
                    // para ese archivo puntual, pero no se tumba el proceso.
                    return originalPath;
                }
                BufferedImage original = ImageIO.read(originalPath.toFile());
                if (original == null) return originalPath;

                BufferedImage thumb = highQualityScale(original, THUMB_WIDTH);

                // Escribir a temporal y mover atómico — un GET concurrente jamás
                // ve un .thumb.jpg a medio escribir.
                Path tmp = thumbPath.resolveSibling(thumbPath.getFileName() + ".tmp");
                if (!ImageIO.write(thumb, "jpg", tmp.toFile())) {
                    Files.deleteIfExists(tmp);
                    return originalPath;
                }
                Files.move(tmp, thumbPath,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return thumbPath;
            } catch (Exception e) {
                return originalPath;
            }
        }
    }

    /**
     * Tope de píxeles que aceptamos decodificar para generar un thumbnail.
     *
     * 50 MP cubre cualquier cámara o escáner realista (una foto de 50 MP ya es
     * de gama alta) y acota la memoria: ImageIO decodifica a un BufferedImage
     * sin comprimir, ~4 bytes por píxel, así que 50 MP son ~200 MB de heap.
     * Con el contenedor limitado a 1 GB y MaxRAMPercentage=75, dos peticiones
     * concurrentes sobre una imagen más grande bastan para tumbar la JVM.
     *
     * El riesgo real es la "decompression bomb": un PNG de pocos KB puede
     * declarar decenas de miles de píxeles por lado. El tamaño en disco no
     * dice nada, así que el límite de 50 MB de subida no cubre este caso —
     * hay que mirar las dimensiones declaradas en la cabecera.
     */
    private static final long MAX_THUMB_PIXELS = 50_000_000L;

    /**
     * Lee SOLO la cabecera de la imagen para conocer sus dimensiones, sin
     * decodificar los píxeles. Es la diferencia entre enterarse del tamaño y
     * sufrirlo: ImageIO.read() reservaría el buffer completo antes de que
     * pudiéramos comprobar nada.
     *
     * Ante la duda (formato ilegible, error de E/S) devuelve true — preferimos
     * no generar el thumb antes que arriesgar la decodificación.
     */
    private static boolean exceedsPixelBudget(Path imagePath) {
        try (javax.imageio.stream.ImageInputStream in =
                     ImageIO.createImageInputStream(imagePath.toFile())) {
            if (in == null) return true;
            java.util.Iterator<javax.imageio.ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return true;
            javax.imageio.ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                long pixels = (long) reader.getWidth(0) * (long) reader.getHeight(0);
                return pixels > MAX_THUMB_PIXELS;
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Downscale multi-step: cada iteración reduce a la mitad hasta acercarse
     * al ancho objetivo. Es la receta clásica (Chris Campbell) para evitar el
     * aliasing que produce un único drawImage a tamaño chico, manteniendo
     * tiempos de ~50-200 ms para fotos grandes.
     */
    private static BufferedImage highQualityScale(BufferedImage source, int targetWidth) {
        int w = source.getWidth();
        int h = source.getHeight();
        if (w <= targetWidth) {
            return source; // no upscale
        }
        int targetHeight = Math.max(1, (int) Math.round(h * ((double) targetWidth / w)));

        BufferedImage current = source;
        int curW = w, curH = h;
        while (curW > targetWidth * 2) {
            curW = Math.max(targetWidth, curW / 2);
            curH = Math.max(targetHeight, curH / 2);
            current = scaleStep(current, curW, curH);
        }
        return scaleStep(current, targetWidth, targetHeight);
    }

    private static BufferedImage scaleStep(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    public Path getPath(String category, String filename) {
        return resolvePath(category, filename);
    }

    /**
     * Devuelve el path relativo al uploadRoot, con cada segmento URL-encodeado,
     * para usar como path interno en el header X-Accel-Redirect (nginx sendfile).
     * Ej: /app/uploads/perfiles/mi foto.jpg → "perfiles/mi%20foto.jpg"
     */
    public String relativizeFromRoot(Path absolutePath) {
        Path normalized = absolutePath.normalize();
        if (!normalized.startsWith(uploadRoot)) {
            throw new IllegalArgumentException("Path fuera del upload root");
        }
        String rel = uploadRoot.relativize(normalized).toString().replace(java.io.File.separatorChar, '/');
        StringBuilder encoded = new StringBuilder();
        for (String segment : rel.split("/")) {
            if (encoded.length() > 0) encoded.append('/');
            encoded.append(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return encoded.toString();
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
