package com.biblioteca.service;

import com.biblioteca.dto.*;
import com.biblioteca.model.Content;
import com.biblioteca.model.Queja;
import com.biblioteca.model.Seguridad;
import com.biblioteca.model.SystemConfig;
import com.biblioteca.repository.*;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DashboardService {

    private final QuejaRepository quejaRepository;
    private final SeguridadRepository seguridadRepository;
    private final SystemConfigRepository configRepository;
    private final ContentRepository contentRepository;

    public DashboardService(QuejaRepository quejaRepository, SeguridadRepository seguridadRepository,
                            SystemConfigRepository configRepository, ContentRepository contentRepository) {
        this.quejaRepository = quejaRepository;
        this.seguridadRepository = seguridadRepository;
        this.configRepository = configRepository;
        this.contentRepository = contentRepository;
    }

    public DashboardDto getDashboard() {
        DashboardDto dto = new DashboardDto();

        // Quejas stats
        List<Queja> quejas = quejaRepository.findAllByOrderByFechaDesc();
        Map<String, Long> stats = new HashMap<>();
        stats.put("total", (long) quejas.size());
        stats.put("nuevas", quejas.stream().filter(q -> "Nueva".equals(q.getEstado())).count());
        stats.put("cerradas", quejas.stream().filter(q -> "Cerrada".equals(q.getEstado())).count());
        dto.setQuejasStats(stats);

        // Alertas
        List<Seguridad> alertas = seguridadRepository.findTop3ByOrderByIdDesc();
        dto.setAlertas(alertas.stream().map(this::toSeguridadDto).collect(Collectors.toList()));

        // Days without accidents
        dto.setDiasSinAccidentes(calcularDiasSinAccidentes());

        // Latest docs — exclude role-restricted categories so users without the
        // role don't see their titles/authors. Computing role-aware visibility
        // here would need the SecurityContext; this whitelist is simpler and
        // matches the only category that's actually gated today.
        List<Content> ultimosDocs = contentRepository
                .findTop3ByCategoryNotInOrderByCreatedAtDesc(java.util.Set.of("almacenes"));
        dto.setUltimosDocumentos(ultimosDocs.stream().map(c -> toContentDto(c, null)).collect(Collectors.toList()));

        return dto;
    }

    private long calcularDiasSinAccidentes() {
        Optional<SystemConfig> config = configRepository.findById("fecha_ultimo_accidente");
        if (config.isPresent() && config.get().getValue() != null) {
            try {
                LocalDate lastDate = LocalDate.parse(config.get().getValue());
                return ChronoUnit.DAYS.between(lastDate, LocalDate.now());
            } catch (Exception e) {
                return 0;
            }
        }
        return 0;
    }

    public void resetAccidentDate(String dateStr) {
        String val = (dateStr != null && !dateStr.isEmpty()) ? dateStr : LocalDate.now().toString();
        SystemConfig config = configRepository.findById("fecha_ultimo_accidente")
                .orElse(new SystemConfig());
        config.setKey("fecha_ultimo_accidente");
        config.setValue(val);
        configRepository.save(config);
    }

    private SeguridadDto toSeguridadDto(Seguridad s) {
        SeguridadDto dto = new SeguridadDto();
        dto.setId(s.getId());
        dto.setTitulo(s.getTitulo());
        dto.setDescripcion(s.getDescripcion());
        dto.setNivel(s.getNivel());
        dto.setFecha(s.getFecha());
        return dto;
    }

    public ContentDto toContentDto(Content c, String baseUrl) {
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
        if (baseUrl != null) {
            dto.setFileUrl(baseUrl + "/api/files/" + c.getCategory() + "/" + c.getFilename());
            if (c.getCoverImage() != null) {
                dto.setCoverUrl(baseUrl + "/api/files/" + c.getCategory() + "/" + c.getCoverImage());
            }
        }
        return dto;
    }
}
