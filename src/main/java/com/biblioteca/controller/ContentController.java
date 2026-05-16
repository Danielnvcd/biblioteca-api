package com.biblioteca.controller;

import com.biblioteca.dto.CommentDto;
import com.biblioteca.dto.ContentDto;
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
            @AuthenticationPrincipal UserPrincipal principal) {

        if ("almacenes".equalsIgnoreCase(category)) {
            Permissions.requireAlmacenView(principal);
        }

        List<Content> items;
        if (categoria != null && !categoria.isEmpty()) {
            items = contentRepository.findByCategoryAndManualCategoryOrderByCreatedAtDesc(category, categoria);
        } else {
            items = contentRepository.findByCategoryOrderByCreatedAtDesc(category);
        }
        List<Category> cats = categoryRepository.findByTypeOrderBySortOrder(category);
        List<String> categorias = cats.stream().map(Category::getName).collect(Collectors.toList());

        // For commentable categories (boletin, lecciones), batch-load comments.
        boolean commentable = "boletin".equalsIgnoreCase(category) || "lecciones".equalsIgnoreCase(category);
        Map<String, List<Comment>> commentsByContent = Map.of();
        if (commentable && !items.isEmpty()) {
            List<String> ids = items.stream().map(Content::getId).collect(Collectors.toList());
            commentsByContent = commentRepository.findByContentIdInOrderByCreatedAtAsc(ids).stream()
                    .collect(Collectors.groupingBy(Comment::getContentId));
        }
        final Map<String, List<Comment>> commentsMap = commentsByContent;

        List<ContentDto> dtos = items.stream().map(c -> {
            ContentDto dto = toDto(c, null);
            if (commentable) {
                List<Comment> cs = commentsMap.getOrDefault(c.getId(), List.of());
                dto.setComments(cs.stream().map(this::toCommentDto).collect(Collectors.toList()));
            }
            return dto;
        }).collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("items", dtos);
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

    @PostMapping("/{contentId}/comment")
    public ResponseEntity<Map<String, String>> addComment(@PathVariable String contentId,
                                                          @RequestParam String text,
                                                          @AuthenticationPrincipal UserPrincipal principal) {
        if (text == null || text.isBlank()) {
            throw ApiException.badRequest("El comentario no puede estar vacío");
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
