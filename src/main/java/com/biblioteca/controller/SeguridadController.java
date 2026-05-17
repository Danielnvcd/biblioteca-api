package com.biblioteca.controller;

import com.biblioteca.dto.CreateCuestionarioRequest;
import com.biblioteca.dto.SeguridadDto;
import com.biblioteca.exception.ApiException;
import com.biblioteca.model.*;
import com.biblioteca.repository.*;
import com.biblioteca.security.Permissions;
import com.biblioteca.security.UserPrincipal;
import com.biblioteca.service.FileStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/seguridad")
@Validated
public class SeguridadController {

    private final SeguridadRepository seguridadRepository;
    private final ContentRepository contentRepository;
    private final QuestionnaireRepository questionnaireRepository;
    private final QuestionnaireResponseRepository responseRepository;
    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper;

    public SeguridadController(SeguridadRepository seguridadRepository, ContentRepository contentRepository,
                               QuestionnaireRepository questionnaireRepository,
                               QuestionnaireResponseRepository responseRepository,
                               FileStorageService fileStorageService,
                               ObjectMapper objectMapper) {
        this.seguridadRepository = seguridadRepository;
        this.contentRepository = contentRepository;
        this.questionnaireRepository = questionnaireRepository;
        this.responseRepository = responseRepository;
        this.fileStorageService = fileStorageService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getSeguridad(@RequestParam(defaultValue = "alertas") String tab) {
        Map<String, Object> response = new HashMap<>();

        List<Seguridad> alertas = seguridadRepository.findAllByOrderByFechaDesc();
        response.put("alertas", alertas.stream().map(this::toDto).collect(Collectors.toList()));

        List<Content> generalFiles = contentRepository.findByCategoryAndManualCategoryOrderByCreatedAtDesc("seguridad", "general");
        response.put("general", generalFiles.stream().map(this::toContentDto).collect(Collectors.toList()));

        List<Content> podcasts = contentRepository.findByCategoryAndManualCategoryOrderByCreatedAtDesc("seguridad", "podcast");
        response.put("podcasts", podcasts.stream().map(this::toContentDto).collect(Collectors.toList()));

        List<Content> videos = contentRepository.findByCategoryAndManualCategoryOrderByCreatedAtDesc("seguridad", "video");
        response.put("videos", videos.stream().map(this::toContentDto).collect(Collectors.toList()));

        List<Questionnaire> cuestionarios = questionnaireRepository.findAllByOrderByCreatedAtDesc();
        List<Map<String, Object>> qList = cuestionarios.stream().map(q -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", q.getId());
            m.put("title", q.getTitle());
            m.put("description", q.getDescription());
            m.put("createdBy", q.getCreatedBy());
            m.put("createdAt", q.getCreatedAt());
            try {
                m.put("questions", objectMapper.readValue(q.getQuestions(), List.class));
            } catch (Exception e) {
                m.put("questions", List.of());
            }
            m.put("responsesCount", responseRepository.countByQuestionnaireId(q.getId()));
            return m;
        }).collect(Collectors.toList());
        response.put("cuestionarios", qList);

        response.put("currentTab", tab);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/alerta")
    public ResponseEntity<Map<String, String>> createAlerta(@Valid @RequestBody SeguridadDto dto,
                                                            @AuthenticationPrincipal UserPrincipal principal) {
        Permissions.requireAdmin(principal);

        Seguridad s = new Seguridad();
        s.setTitulo(dto.getTitulo());
        s.setDescripcion(dto.getDescripcion());
        s.setNivel(dto.getNivel() != null ? dto.getNivel() : "info");
        s.setFecha(LocalDate.now());
        s.setCreatedAt(LocalDateTime.now());
        seguridadRepository.save(s);
        return ResponseEntity.ok(Map.of("message", "Alerta publicada"));
    }

    @PostMapping("/alerta/delete/{id}")
    public ResponseEntity<Map<String, String>> deleteAlerta(@PathVariable Integer id,
                                                            @AuthenticationPrincipal UserPrincipal principal) {
        Permissions.requireSuperAdmin(principal);
        if (!seguridadRepository.existsById(id)) {
            throw ApiException.notFound("Alerta no encontrada");
        }
        seguridadRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "Alerta eliminada"));
    }

    @PostMapping("/general/upload")
    public ResponseEntity<Map<String, String>> uploadGeneral(
            @RequestParam("file") MultipartFile file,
            @RequestParam("titulo") @NotBlank @Size(max = 150) String title,
            @RequestParam(required = false) @Size(max = 4000) String descripcion,
            @AuthenticationPrincipal UserPrincipal principal) {
        Permissions.requireAdmin(principal);

        String filename = fileStorageService.store(file, "seguridad");
        Content c = new Content();
        c.setId(UUID.randomUUID().toString());
        c.setTitle(title);
        c.setDescription(descripcion);
        c.setFilename(filename);
        c.setCategory("seguridad");
        c.setManualCategory("general");
        c.setAuthor(principal.getUsername());
        c.setCreatedAt(LocalDateTime.now());
        contentRepository.save(c);
        return ResponseEntity.ok(Map.of("message", "Archivo subido"));
    }

    @PostMapping("/podcast")
    public ResponseEntity<Map<String, String>> createPodcast(
            @RequestParam("audio") MultipartFile audio,
            @RequestParam(required = false) MultipartFile imagen,
            @RequestParam("titulo") @NotBlank @Size(max = 150) String title,
            @RequestParam(required = false) @Size(max = 4000) String descripcion,
            @AuthenticationPrincipal UserPrincipal principal) {
        Permissions.requireAdmin(principal);

        String audioName = fileStorageService.store(audio, "seguridad");
        String imgName = (imagen != null && !imagen.isEmpty()) ? fileStorageService.store(imagen, "seguridad") : null;
        Content c = new Content();
        c.setId(UUID.randomUUID().toString());
        c.setTitle(title);
        c.setDescription(descripcion);
        c.setFilename(audioName);
        c.setCoverImage(imgName);
        c.setCategory("seguridad");
        c.setManualCategory("podcast");
        c.setAuthor(principal.getUsername());
        c.setCreatedAt(LocalDateTime.now());
        contentRepository.save(c);
        return ResponseEntity.ok(Map.of("message", "Podcast publicado"));
    }

    @PostMapping("/video")
    public ResponseEntity<Map<String, String>> createVideo(
            @RequestParam("titulo") @NotBlank @Size(max = 150) String title,
            @RequestParam("videoUrl") @NotBlank @Size(max = 500) String videoUrl,
            @RequestParam(required = false) @Size(max = 4000) String descripcion,
            @AuthenticationPrincipal UserPrincipal principal) {
        Permissions.requireAdmin(principal);

        // Reject javascript:, data:, file:, vbscript:, etc. — would XSS via <iframe src>.
        if (videoUrl == null || videoUrl.isBlank()) {
            throw ApiException.badRequest("La URL del video es requerida");
        }
        String lower = videoUrl.trim().toLowerCase();
        if (!lower.startsWith("https://")) {
            // http:// was previously allowed but exposes users to mixed-content
            // warnings and on-the-wire tampering. Require TLS.
            throw ApiException.badRequest("La URL debe comenzar con https://");
        }

        Content c = new Content();
        c.setId(UUID.randomUUID().toString());
        c.setTitle(title);
        c.setDescription(descripcion);
        c.setFilename("video_link");
        c.setLinkUrl(videoUrl);
        c.setCategory("seguridad");
        c.setManualCategory("video");
        c.setAuthor(principal.getUsername());
        c.setCreatedAt(LocalDateTime.now());
        contentRepository.save(c);
        return ResponseEntity.ok(Map.of("message", "Video publicado"));
    }

    @PostMapping("/cuestionario")
    public ResponseEntity<Map<String, String>> createCuestionario(@Valid @RequestBody CreateCuestionarioRequest body,
                                                                  @AuthenticationPrincipal UserPrincipal principal) {
        Permissions.requireAdmin(principal);

        Questionnaire q = new Questionnaire();
        q.setTitle(body.getTitle());
        q.setDescription(body.getDescription());
        q.setQuestions(objectMapper.valueToTree(body.getQuestions()).toString());
        q.setCreatedBy(principal.getUsername());
        q.setCreatedAt(LocalDateTime.now());
        questionnaireRepository.save(q);
        return ResponseEntity.ok(Map.of("message", "Cuestionario creado"));
    }

    /** Tamaños máximos para respuestas de cuestionario. Las keys son dinámicas
     *  (dependen de las preguntas del cuestionario) así que no podemos usar
     *  Bean Validation directamente sobre Map; validamos manualmente. */
    private static final int MAX_ANSWERS = 100;
    private static final int MAX_ANSWER_KEY_LEN = 200;
    private static final int MAX_ANSWER_VALUE_LEN = 4000;

    @PostMapping("/cuestionario/{id}/responder")
    public ResponseEntity<Map<String, String>> responder(@PathVariable Integer id,
                                                         @RequestBody Map<String, String> answers,
                                                         @AuthenticationPrincipal UserPrincipal principal) {
        if (!questionnaireRepository.existsById(id)) {
            throw ApiException.notFound("Cuestionario no encontrado");
        }
        if (answers == null || answers.isEmpty()) {
            throw ApiException.badRequest("Debes responder al menos una pregunta");
        }
        if (answers.size() > MAX_ANSWERS) {
            throw ApiException.badRequest("Demasiadas respuestas (máx " + MAX_ANSWERS + ")");
        }
        for (Map.Entry<String, String> e : answers.entrySet()) {
            if (e.getKey() != null && e.getKey().length() > MAX_ANSWER_KEY_LEN) {
                throw ApiException.badRequest("Identificador de pregunta demasiado largo");
            }
            if (e.getValue() != null && e.getValue().length() > MAX_ANSWER_VALUE_LEN) {
                throw ApiException.badRequest("Respuesta demasiado larga (máx " + MAX_ANSWER_VALUE_LEN + " caracteres)");
            }
        }
        // One response per user per questionnaire — prevents spam / duplicates.
        if (responseRepository.existsByQuestionnaireIdAndUserName(id, principal.getUsername())) {
            throw ApiException.badRequest("Ya respondiste este cuestionario");
        }
        QuestionnaireResponse r = new QuestionnaireResponse();
        r.setQuestionnaireId(id);
        r.setUserName(principal.getUsername());
        try {
            r.setAnswers(objectMapper.writeValueAsString(answers));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw ApiException.badRequest("Error procesando respuestas");
        }
        r.setCreatedAt(LocalDateTime.now());
        responseRepository.save(r);
        return ResponseEntity.ok(Map.of("message", "Respuestas enviadas"));
    }

    @PostMapping("/cuestionario/delete/{id}")
    public ResponseEntity<Map<String, String>> deleteCuestionario(@PathVariable Integer id,
                                                                  @AuthenticationPrincipal UserPrincipal principal) {
        Permissions.requireSuperAdmin(principal);
        if (!questionnaireRepository.existsById(id)) {
            throw ApiException.notFound("Cuestionario no encontrado");
        }
        questionnaireRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "Cuestionario eliminado"));
    }

    @GetMapping("/cuestionario/{id}/responses")
    public ResponseEntity<List<Map<String, Object>>> listResponses(@PathVariable Integer id,
                                                                    @AuthenticationPrincipal UserPrincipal principal) {
        Permissions.requireAdmin(principal);
        if (!questionnaireRepository.existsById(id)) {
            throw ApiException.notFound("Cuestionario no encontrado");
        }
        List<Map<String, Object>> data = responseRepository
                .findByQuestionnaireIdOrderByCreatedAtDesc(id)
                .stream()
                .map(r -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", r.getId());
                    m.put("userName", r.getUserName());
                    m.put("createdAt", r.getCreatedAt());
                    try {
                        m.put("answers", objectMapper.readValue(r.getAnswers(), Map.class));
                    } catch (Exception e) {
                        m.put("answers", Map.of());
                    }
                    return m;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(data);
    }

    private SeguridadDto toDto(Seguridad s) {
        SeguridadDto dto = new SeguridadDto();
        dto.setId(s.getId());
        dto.setTitulo(s.getTitulo());
        dto.setDescripcion(s.getDescripcion());
        dto.setNivel(s.getNivel());
        dto.setFecha(s.getFecha());
        return dto;
    }

    private Map<String, Object> toContentDto(Content c) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", c.getId());
        m.put("title", c.getTitle());
        m.put("description", c.getDescription());
        m.put("filename", c.getFilename());
        m.put("coverImage", c.getCoverImage());
        m.put("linkUrl", c.getLinkUrl());
        m.put("author", c.getAuthor());
        m.put("createdAt", c.getCreatedAt());
        return m;
    }
}
