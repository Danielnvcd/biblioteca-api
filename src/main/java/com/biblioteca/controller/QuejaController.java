package com.biblioteca.controller;

import com.biblioteca.dto.PagedResponse;
import com.biblioteca.dto.QuejaDto;
import com.biblioteca.exception.ApiException;
import com.biblioteca.model.Queja;
import com.biblioteca.repository.QuejaRepository;
import com.biblioteca.security.Permissions;
import com.biblioteca.security.UserPrincipal;
import com.biblioteca.service.FileStorageService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.validation.Valid;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/quejas")
@Validated
public class QuejaController {

    private final QuejaRepository quejaRepository;
    private final FileStorageService fileStorageService;

    public QuejaController(QuejaRepository quejaRepository, FileStorageService fileStorageService) {
        this.quejaRepository = quejaRepository;
        this.fileStorageService = fileStorageService;
    }

    @GetMapping
    public ResponseEntity<PagedResponse<QuejaDto>> list(
            @RequestParam(required = false) String estado,
            @RequestParam(required = false) String q,
            @PageableDefault(size = 20, sort = "fecha", direction = Sort.Direction.DESC)
            Pageable pageable) {
        String estadoFilter = (estado != null) ? estado.trim() : "";
        String search       = (q != null) ? q.trim() : "";
        Page<Queja> page = quejaRepository.search(estadoFilter, search, pageable);
        return ResponseEntity.ok(PagedResponse.from(page, this::toDto));
    }

    @PostMapping("/create")
    public ResponseEntity<Map<String, String>> create(@Valid @RequestBody QuejaDto dto,
                                                      @AuthenticationPrincipal UserPrincipal principal) {
        Permissions.requireAdmin(principal);

        Queja queja = new Queja();
        queja.setFolio(dto.getFolio());
        queja.setCliente(dto.getCliente());
        queja.setMotivo(dto.getMotivo());
        queja.setFecha(dto.getFecha() != null ? dto.getFecha() : LocalDate.now());
        queja.setSolucion(dto.getSolucion());
        queja.setEstado("Nueva");
        queja.setCreatedAt(LocalDateTime.now());
        quejaRepository.save(queja);
        return ResponseEntity.ok(Map.of("message", "Queja registrada"));
    }

    @PostMapping("/edit/{id}")
    public ResponseEntity<Map<String, String>> edit(@PathVariable Integer id, @Valid @RequestBody QuejaDto dto,
                                                    @AuthenticationPrincipal UserPrincipal principal) {
        Permissions.requireAdmin(principal);

        Queja queja = quejaRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Queja no encontrada"));
        queja.setSolucion(dto.getSolucion());
        if (dto.getEstado() != null) queja.setEstado(dto.getEstado());
        quejaRepository.save(queja);
        return ResponseEntity.ok(Map.of("message", "Queja actualizada"));
    }

    @PostMapping("/{id}/upload-solution")
    public ResponseEntity<Map<String, String>> uploadSolution(@PathVariable Integer id,
                                                              @RequestParam("file") MultipartFile file,
                                                              @AuthenticationPrincipal UserPrincipal principal) {
        Permissions.requireAdmin(principal);

        Queja queja = quejaRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Queja no encontrada"));
        String filename = fileStorageService.store(file, "quejas");
        if (queja.getSolucionImagen() != null) {
            fileStorageService.delete(queja.getSolucionImagen(), "quejas");
        }
        queja.setSolucionImagen(filename);
        quejaRepository.save(queja);
        return ResponseEntity.ok(Map.of("message", "Archivo subido"));
    }

    @PostMapping("/delete/{id}")
    public ResponseEntity<Map<String, String>> delete(@PathVariable Integer id,
                                                      @AuthenticationPrincipal UserPrincipal principal) {
        Permissions.requireSuperAdmin(principal);

        Queja queja = quejaRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Queja no encontrada"));
        if (queja.getSolucionImagen() != null) {
            fileStorageService.delete(queja.getSolucionImagen(), "quejas");
        }
        quejaRepository.delete(queja);
        return ResponseEntity.ok(Map.of("message", "Queja eliminada"));
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Long>> stats() {
        List<Queja> all = quejaRepository.findAllByOrderByFechaDesc();
        Map<String, Long> m = Map.of(
            "total", (long) all.size(),
            "nuevas", all.stream().filter(q -> "Nueva".equals(q.getEstado())).count(),
            "cerradas", all.stream().filter(q -> "Cerrada".equals(q.getEstado())).count()
        );
        return ResponseEntity.ok(m);
    }

    private QuejaDto toDto(Queja q) {
        QuejaDto dto = new QuejaDto();
        dto.setId(q.getId());
        dto.setFolio(q.getFolio());
        dto.setFecha(q.getFecha());
        dto.setCliente(q.getCliente());
        dto.setMotivo(q.getMotivo());
        dto.setSolucion(q.getSolucion());
        dto.setEstado(q.getEstado());
        dto.setSolucionImagen(q.getSolucionImagen());
        dto.setCreatedAt(q.getCreatedAt());
        return dto;
    }
}
