package com.biblioteca.controller;

import com.biblioteca.dto.CommentDto;
import com.biblioteca.dto.ContentDto;
import com.biblioteca.dto.PagedResponse;
import com.biblioteca.exception.ApiException;
import com.biblioteca.model.Category;
import com.biblioteca.model.Comment;
import com.biblioteca.model.Content;
import com.biblioteca.repository.CategoryRepository;
import com.biblioteca.repository.CommentRepository;
import com.biblioteca.repository.ContentRepository;
import com.biblioteca.security.Permissions;
import com.biblioteca.security.UserPrincipal;
import com.biblioteca.service.FileStorageService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/content")
public class ContentController {

    private final ContentRepository contentRepository;
    private final CommentRepository commentRepository;
    private final CategoryRepository categoryRepository;
    private final FileStorageService fileStorageService;

    public ContentController(ContentRepository contentRepository, CommentRepository commentRepository,
                             CategoryRepository categoryRepository, FileStorageService fileStorageService) {
        this.contentRepository = contentRepository;
        this.commentRepository = commentRepository;
        this.categoryRepository = categoryRepository;
        this.fileStorageService = fileStorageService;
    }

    @GetMapping("/{category}")
    public ResponseEntity<Map<String, Object>> getByCategory(
            @PathVariable String category,
            @RequestParam(required = false) String categoria,
            @RequestParam(required = false) String q,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable,
            @AuthenticationPrincipal UserPrincipal principal) {

        if ("almacenes".equalsIgnoreCase(category)) {
            Permissions.requireAlmacenView(principal);
        }

        // Empty string ("") means "no filter" downstream — see repository javadoc.
        String manualCat = (categoria != null) ? categoria.trim() : "";
        String search    = (q != null) ? q.trim() : "";
        Page<Content> page = contentRepository.search(category, manualCat, search, pageable);

        List<Category> cats = categoryRepository.findByTypeOrderBySortOrder(category);
        List<String> categorias = cats.stream().map(Category::getName).collect(Collectors.toList());

        // For commentable categories (boletin, lecciones), batch-load comments
        // only for the IDs in the current page.
        boolean commentable = "boletin".equalsIgnoreCase(category) || "lecciones".equalsIgnoreCase(category);
        Map<String, List<Comment>> commentsMap;
        if (commentable && !page.getContent().isEmpty()) {
            List<String> ids = page.getContent().stream().map(Content::getId).collect(Collectors.toList());
            commentsMap = commentRepository.findByContentIdInOrderByCreatedAtAsc(ids).stream()
                    .collect(Collectors.groupingBy(Comment::getContentId));
        } else {
            commentsMap = Map.of();
        }

        PagedResponse<ContentDto> items = PagedResponse.from(page, c -> {
            ContentDto dto = toDto(c, null);
            if (commentable) {
                List<Comment> cs = commentsMap.getOrDefault(c.getId(), List.of());
                dto.setComments(cs.stream().map(this::toCommentDto).collect(Collectors.toList()));
            }
            return dto;
        });

        Map<String, Object> response = new HashMap<>();
        response.put("items", items);
        response.put("categorias", categorias);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{category}/upload")
    public ResponseEntity<ContentDto> upload(
            @PathVariable String category,
            @RequestParam("file") MultipartFile file,
            @RequestParam("titulo") String title,
            @RequestParam(required = false) String descripcion,
            @RequestParam(required = false) String manualCategory,
            @AuthenticationPrincipal UserPrincipal principal) {

        Permissions.requireUploadFor(principal, category);

        String filename = fileStorageService.store(file, category);
        Content content = new Content();
        content.setId(UUID.randomUUID().toString());
        content.setTitle(title.length() > 150 ? title.substring(0, 150) : title);
        content.setDescription(descripcion);
        content.setFilename(filename);
        content.setCategory(category);
        content.setManualCategory(manualCategory);
        content.setAuthor(principal.getUsername());
        content.setCreatedAt(LocalDateTime.now());
        contentRepository.save(content);
        return ResponseEntity.ok(toDto(content, null));
    }

    @PostMapping("/{category}/delete/{id}")
    public ResponseEntity<Map<String, String>> delete(@PathVariable String category, @PathVariable String id,
                                                      @AuthenticationPrincipal UserPrincipal principal) {
        Content content = contentRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Contenido no encontrado"));

        // Anti-IDOR: enforce permission on the real category of the row, not the URL.
        // Without this, /content/lecciones/delete/{id-de-almacenes} bypasses the
        // stricter almacenes role check.
        if (!category.equalsIgnoreCase(content.getCategory())) {
            throw ApiException.forbidden("La categoría del recurso no coincide con la ruta");
        }
        Permissions.requireDeleteFor(principal, content.getCategory());

        fileStorageService.delete(content.getFilename(), content.getCategory());
        if (content.getCoverImage() != null) {
            fileStorageService.delete(content.getCoverImage(), content.getCategory());
        }
        contentRepository.delete(content);
        return ResponseEntity.ok(Map.of("message", "Eliminado correctamente"));
    }

    private static final int COMMENT_MAX_LENGTH = 1000;
    private static final java.util.Set<String> COMMENTABLE_CATEGORIES =
            java.util.Set.of("boletin", "lecciones");

    @PostMapping("/{contentId}/comment")
    public ResponseEntity<Map<String, String>> addComment(@PathVariable String contentId,
                                                          @RequestParam String text,
                                                          @AuthenticationPrincipal UserPrincipal principal) {
        if (text == null || text.isBlank()) {
            throw ApiException.badRequest("El comentario no puede estar vacío");
        }
        if (text.length() > COMMENT_MAX_LENGTH) {
            throw ApiException.badRequest(
                    "El comentario es demasiado largo (máx " + COMMENT_MAX_LENGTH + " caracteres)");
        }
        Content content = contentRepository.findById(contentId)
                .orElseThrow(() -> ApiException.notFound("Contenido no encontrado"));
        if (!COMMENTABLE_CATEGORIES.contains(content.getCategory().toLowerCase())) {
            throw ApiException.badRequest("Esta categoría no admite comentarios");
        }
        Comment comment = new Comment();
        comment.setContentId(contentId);
        comment.setUserName(principal.getUsername());
        comment.setText(text);
        comment.setCreatedAt(LocalDateTime.now());
        commentRepository.save(comment);
        return ResponseEntity.ok(Map.of("message", "Comentario agregado"));
    }

    @GetMapping("/categories/{type}")
    public ResponseEntity<List<Category>> getCategories(@PathVariable String type) {
        return ResponseEntity.ok(categoryRepository.findByTypeOrderBySortOrder(type));
    }

    private ContentDto toDto(Content c, String baseUrl) {
        ContentDto dto = new ContentDto();
        dto.setId(c.getId());
        dto.setTitle(c.getTitle());
        dto.setDescription(c.getDescription());
        dto.setFilename(c.getFilename());
        dto.setCategory(c.getCategory());
        dto.setManualCategory(c.getManualCategory());
        dto.setAuthor(c.getAuthor());
        dto.setCreatedAt(c.getCreatedAt());
        dto.setCoverImage(c.getCoverImage());
        dto.setLinkUrl(c.getLinkUrl());
        return dto;
    }

    private CommentDto toCommentDto(Comment c) {
        CommentDto dto = new CommentDto();
        dto.setId(c.getId());
        dto.setUserName(c.getUserName());
        dto.setText(c.getText());
        dto.setCreatedAt(c.getCreatedAt());
        return dto;
    }
}
