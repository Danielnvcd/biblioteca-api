package com.biblioteca.controller;

import com.biblioteca.security.Permissions;
import com.biblioteca.security.UserPrincipal;
import com.biblioteca.service.FileStorageService;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/files")
public class FileController {

    /**
     * Categorías que pueden ser servidas sin autenticación. Cumplen DOS
     * condiciones simultáneamente:
     *   1. El frontend las renderiza con `<img src=...>` directo, que no
     *      puede mandar Authorization header.
     *   2. El contenido es genuinamente difundible dentro de la empresa.
     *
     * Política de subida por categoría:
     *
     *   perfiles  → fotos de avatar de usuarios. NO incluir documentos
     *               personales, INE, CURP, comprobantes de domicilio, ni
     *               nada que identifique al usuario fuera del nombre.
     *
     *   boletin   → imágenes del newsletter interno. NO incluir datos de
     *               clientes, números de empleado, salarios, ni evidencia
     *               de incidentes.
     *
     *   seguridad → avisos generales (cartelería, infografías, manuales
     *               sin datos personales). NO incluir reportes de incidentes
     *               con nombres de personas o ubicaciones específicas.
     *
     * Toda categoría con datos sensibles (quejas con evidencia de cliente,
     * almacenes con info operativa, etc.) NO va aquí. El frontend debe
     * descargarlas vía axios autenticado y convertirlas a blob URL para
     * mostrarlas — ver useAuthenticatedImage en biblioteca-frontend.
     *
     * Antes de agregar una nueva categoría a este set, validar contra ambos
     * criterios. Si tienes dudas, déjala fuera y usa el patrón de blob URL.
     */
    private static final Set<String> PUBLIC_CATEGORIES = Set.of(
            "perfiles", "boletin", "seguridad");
    /** Sub-conjunto de no-públicas que además requieren rol específico. */
    private static final Set<String> ROLE_RESTRICTED = Set.of("almacenes");

    private final FileStorageService fileStorageService;

    public FileController(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    @GetMapping("/{category}/{filename}")
    public ResponseEntity<Resource> getFile(@PathVariable String category, @PathVariable String filename, @RequestParam(value = "thumb", required = false, defaultValue = "false") boolean thumb) {
        String cat = category.toLowerCase(java.util.Locale.ROOT);
        if (!PUBLIC_CATEGORIES.contains(cat)) {
            UserPrincipal principal = currentPrincipal();
            if (principal == null) {
                return ResponseEntity.status(401).build();
            }
            if (ROLE_RESTRICTED.contains(cat)
                    && "almacenes".equals(cat)
                    && !Permissions.canDownloadAlmacenes(principal)) {
                return ResponseEntity.status(403).build();
            }
        }

        try {
            Path filePath = thumb ? fileStorageService.getThumbnailPath(category, filename) : fileStorageService.getPath(category, filename);
            Resource resource = new UrlResource(filePath.toUri());
            if (resource.exists() && resource.isReadable()) {
                // El thumb generado siempre es JPG aunque el original sea .png/.gif,
                // así que el content-type debe salir del archivo realmente servido.
                String contentType = determineContentType(filePath.getFileName().toString());
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(contentType))
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                        .cacheControl(CacheControl.maxAge(30, TimeUnit.DAYS).cachePrivate())
                        .header("X-Content-Type-Options", "nosniff")
                        .body(resource);
            }
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception ignored) {
        }
        return ResponseEntity.notFound().build();
    }

    private static UserPrincipal currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        Object p = auth.getPrincipal();
        return p instanceof UserPrincipal up ? up : null;
    }

    private String determineContentType(String filename) {
        String ext = filename.contains(".") ? filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT) : "";
        return switch (ext) {
            case "pdf" -> "application/pdf";
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "mp4" -> "video/mp4";
            case "webm" -> "video/webm";
            case "mov" -> "video/quicktime";
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "m4a" -> "audio/mp4";
            case "ogg" -> "audio/ogg";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls" -> "application/vnd.ms-excel";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ppt" -> "application/vnd.ms-powerpoint";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            default -> "application/octet-stream";
        };
    }
}
