package com.biblioteca.controller;

import com.biblioteca.security.Permissions;
import com.biblioteca.security.UserPrincipal;
import com.biblioteca.service.FileStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
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

    /**
     * Si está activo, en vez de leer el archivo y devolverlo en el body, se
     * responde con header `X-Accel-Redirect` apuntando a una location interna
     * de nginx (sendfile directo desde disco). La auth/role checks siguen
     * pasando por este controller — solo el byte streaming se delega a nginx.
     *
     * Se mantiene apagado en dev (no hay nginx en localhost), encendido en prod
     * vía application-prod.yml.
     */
    @Value("${app.internal-redirect.enabled:false}")
    private boolean internalRedirectEnabled;

    @Value("${app.internal-redirect.prefix:/_protected}")
    private String internalRedirectPrefix;

    public FileController(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    @GetMapping("/{category}/{filename}")
    public ResponseEntity<?> getFile(@PathVariable String category, @PathVariable String filename, @RequestParam(value = "thumb", required = false, defaultValue = "false") boolean thumb) {
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
            if (!Files.exists(filePath) || !Files.isReadable(filePath)) {
                return ResponseEntity.notFound().build();
            }
            // El thumb generado siempre es JPG aunque el original sea .png/.gif,
            // así que el content-type debe salir del archivo realmente servido.
            String servedName = filePath.getFileName().toString();
            String contentType = determineContentType(servedName);
            // El nombre del header debe describir el archivo REALMENTE servido.
            // Con ?thumb=true el thumb es siempre JPG aunque el original sea
            // .png/.gif, así que usar el nombre original dejaba un
            // Content-Type: image/jpeg junto a un filename="foo.png" — el
            // browser guardaba un JPG con extensión equivocada.
            // Filename llega como @PathVariable y se mete al header — saneamos
            // comillas y CR/LF para que un nombre raro no rompa la cabecera.
            String safeFilename = servedName.replaceAll("[\"\\r\\n]", "_");
            // Para documentos (PDF/Office) forzamos descarga: si un atacante
            // logra subirlos como admin comprometido, no se renderizan en
            // contexto del dominio de la API. Para imágenes/audio/video
            // dejamos inline porque el frontend los embebe vía <img>/<video>.
            String disposition = isDocumentExtension(servedName) ? "attachment" : "inline";

            ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition + "; filename=\"" + safeFilename + "\"")
                    .cacheControl(CacheControl.maxAge(30, TimeUnit.DAYS).cachePrivate())
                    .header("X-Content-Type-Options", "nosniff");

            if (internalRedirectEnabled) {
                // nginx intercepta el header, hace una redirección interna y sirve
                // el archivo con sendfile. El thread del Tomcat se libera ya.
                String redirectPath = internalRedirectPrefix + "/" + fileStorageService.relativizeFromRoot(filePath);
                return builder.header("X-Accel-Redirect", redirectPath).build();
            }
            return builder.body(new UrlResource(filePath.toUri()));
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

    private static final Set<String> DOCUMENT_EXTENSIONS = Set.of(
            "pdf", "doc", "docx", "xls", "xlsx", "xlsm", "ppt", "pptx");

    private boolean isDocumentExtension(String filename) {
        String ext = filename.contains(".")
                ? filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT)
                : "";
        return DOCUMENT_EXTENSIONS.contains(ext);
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
