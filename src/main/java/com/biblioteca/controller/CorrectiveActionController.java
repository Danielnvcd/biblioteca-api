package com.biblioteca.controller;

import com.biblioteca.dto.CorrectionActivityDto;
import com.biblioteca.dto.CorrectiveActionDto;
import com.biblioteca.dto.CreateActivityByFolioRequest;
import com.biblioteca.dto.PagedResponse;
import com.biblioteca.dto.UpdateActivityStatusRequest;
import com.biblioteca.exception.ApiException;
import com.biblioteca.model.CorrectionActivity;
import com.biblioteca.model.CorrectiveAction;
import com.biblioteca.repository.CorrectionActivityRepository;
import com.biblioteca.repository.CorrectiveActionRepository;
import com.biblioteca.security.Permissions;
import com.biblioteca.security.UserPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/acciones")
@Validated
public class CorrectiveActionController {

    private final CorrectiveActionRepository actionRepository;
    private final CorrectionActivityRepository activityRepository;

    public CorrectiveActionController(CorrectiveActionRepository actionRepository,
                                       CorrectionActivityRepository activityRepository) {
        this.actionRepository = actionRepository;
        this.activityRepository = activityRepository;
    }

    @GetMapping
    public ResponseEntity<PagedResponse<CorrectiveActionDto>> list(
            @PageableDefault(size = 20, sort = "fechaReporte", direction = Sort.Direction.DESC)
            Pageable pageable) {
        Page<CorrectiveAction> page = actionRepository.findAllBy(pageable);
        return ResponseEntity.ok(PagedResponse.from(page, this::toDto));
    }

    @PostMapping("/create")
    public ResponseEntity<Map<String, String>> create(@Valid @RequestBody CorrectiveActionDto dto,
                                                      @AuthenticationPrincipal UserPrincipal principal) {
        Permissions.requireAdmin(principal);

        CorrectiveAction action = new CorrectiveAction();
        action.setFolio(dto.getFolio());
        action.setFechaReporte(dto.getFechaReporte() != null ? dto.getFechaReporte() : LocalDate.now());
        action.setOrigen(dto.getOrigen());
        action.setReporta(dto.getReporta());
        action.setDescripcion(dto.getDescripcion());
        action.setMedidaTomada(dto.getMedidaTomada() != null ? dto.getMedidaTomada() : "Acción correctiva");
        action.setDepartamento(dto.getDepartamento());
        action.setFechaEval1(dto.getFechaEval1());
        action.setFechaEval2(dto.getFechaEval2());
        action.setEstatus(dto.getEstatus() != null ? dto.getEstatus() : "En proceso");
        actionRepository.save(action);
        return ResponseEntity.ok(Map.of("message", "Acción correctiva creada"));
    }

    @PostMapping("/edit/{id}")
    public ResponseEntity<Map<String, String>> edit(@PathVariable Integer id, @Valid @RequestBody CorrectiveActionDto dto,
                                                    @AuthenticationPrincipal UserPrincipal principal) {
        Permissions.requireAdmin(principal);

        CorrectiveAction action = actionRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Acción no encontrada"));
        if (dto.getFolio() != null) action.setFolio(dto.getFolio());
        if (dto.getOrigen() != null) action.setOrigen(dto.getOrigen());
        if (dto.getReporta() != null) action.setReporta(dto.getReporta());
        if (dto.getDescripcion() != null) action.setDescripcion(dto.getDescripcion());
        if (dto.getMedidaTomada() != null) action.setMedidaTomada(dto.getMedidaTomada());
        if (dto.getDepartamento() != null) action.setDepartamento(dto.getDepartamento());
        if (dto.getFechaReporte() != null) action.setFechaReporte(dto.getFechaReporte());
        if (dto.getFechaEval1() != null) action.setFechaEval1(dto.getFechaEval1());
        if (dto.getFechaEval2() != null) action.setFechaEval2(dto.getFechaEval2());
        if (dto.getEstatus() != null) action.setEstatus(dto.getEstatus());
        actionRepository.save(action);
        return ResponseEntity.ok(Map.of("message", "Acción actualizada"));
    }

    @PostMapping("/delete/{id}")
    public ResponseEntity<Map<String, String>> delete(@PathVariable Integer id,
                                                      @AuthenticationPrincipal UserPrincipal principal) {
        Permissions.requireAdmin(principal);
        if (!actionRepository.existsById(id)) {
            throw ApiException.notFound("Acción no encontrada");
        }
        actionRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "Acción eliminada"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<CorrectiveActionDto> get(@PathVariable Integer id) {
        CorrectiveAction action = actionRepository.findByIdWithActivities(id)
                .orElseThrow(() -> ApiException.notFound("Acción no encontrada"));
        return ResponseEntity.ok(toDto(action));
    }

    @PostMapping("/{id}/actividad")
    public ResponseEntity<Map<String, String>> addActivity(@PathVariable Integer id,
                                                           @Valid @RequestBody CorrectionActivityDto dto,
                                                           @AuthenticationPrincipal UserPrincipal principal) {
        Permissions.requireAdmin(principal);
        if (!actionRepository.existsById(id)) {
            throw ApiException.notFound("Acción no encontrada");
        }
        CorrectionActivity act = new CorrectionActivity();
        act.setActionId(id);
        act.setDescripcion(dto.getDescripcion());
        act.setResponsable(dto.getResponsable());
        act.setFechaCompromiso(dto.getFechaCompromiso() != null ? dto.getFechaCompromiso() : LocalDate.now());
        act.setEstatus("Pendiente");
        act.setObservaciones(dto.getObservaciones());
        activityRepository.save(act);
        return ResponseEntity.ok(Map.of("message", "Actividad agregada"));
    }

    @GetMapping("/actividades-pendientes")
    public ResponseEntity<List<Map<String, Object>>> pendingActivities() {
        List<CorrectionActivity> activities = activityRepository.findPendingWithAction();
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> data = activities.stream().map(act -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", act.getId());
            m.put("descripcion", act.getDescripcion());
            m.put("responsable", act.getResponsable());
            m.put("fechaCompromiso", act.getFechaCompromiso());
            m.put("estatus", act.getEstatus());
            m.put("observaciones", act.getObservaciones());
            m.put("diasAtraso", act.getFechaCompromiso() != null && act.getFechaCompromiso().isBefore(today) ?
                    ChronoUnit.DAYS.between(act.getFechaCompromiso(), today) : 0);
            m.put("folio", act.getAction() != null ? act.getAction().getFolio() : "N/A");
            return m;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(data);
    }

    @PostMapping("/actividad/{id}/estatus")
    public ResponseEntity<Map<String, String>> updateActivityStatus(@PathVariable Integer id,
                                                                    @Valid @RequestBody UpdateActivityStatusRequest body,
                                                                    @AuthenticationPrincipal UserPrincipal principal) {
        Permissions.requireAdmin(principal);

        CorrectionActivity act = activityRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Actividad no encontrada"));
        if (body.getEstatus() != null) act.setEstatus(body.getEstatus());
        if (body.getObservaciones() != null) act.setObservaciones(body.getObservaciones());
        activityRepository.save(act);
        return ResponseEntity.ok(Map.of("message", "Estatus actualizado"));
    }

    /**
     * Create activity by folio lookup — used from "Actividades pendientes" global page.
     * Mirrors Flask's /acciones/actividad/crear_global.
     */
    @PostMapping("/actividad/crear-global")
    public ResponseEntity<Map<String, String>> createActivityByFolio(@Valid @RequestBody CreateActivityByFolioRequest body,
                                                                      @AuthenticationPrincipal UserPrincipal principal) {
        Permissions.requireAdmin(principal);

        CorrectiveAction action = actionRepository.findByFolio(body.getFolio())
                .orElseThrow(() -> ApiException.notFound("No se encontró ninguna acción correctiva con folio " + body.getFolio()));

        CorrectionActivity act = new CorrectionActivity();
        act.setActionId(action.getId());
        act.setDescripcion(body.getDescripcion());
        act.setResponsable(body.getResponsable());
        act.setFechaCompromiso(body.getFechaCompromiso() != null ? body.getFechaCompromiso() : LocalDate.now());
        act.setEstatus("Pendiente");
        act.setObservaciones(body.getObservaciones());
        activityRepository.save(act);
        return ResponseEntity.ok(Map.of("message", "Actividad agregada"));
    }

    private CorrectiveActionDto toDto(CorrectiveAction a) {
        CorrectiveActionDto dto = new CorrectiveActionDto();
        dto.setId(a.getId());
        dto.setFolio(a.getFolio());
        dto.setFechaReporte(a.getFechaReporte());
        dto.setOrigen(a.getOrigen());
        dto.setReporta(a.getReporta());
        dto.setDescripcion(a.getDescripcion());
        dto.setMedidaTomada(a.getMedidaTomada());
        dto.setDepartamento(a.getDepartamento());
        dto.setFechaEval1(a.getFechaEval1());
        dto.setFechaEval2(a.getFechaEval2());
        dto.setEstatus(a.getEstatus());
        if (a.getActivities() != null) {
            dto.setActivities(a.getActivities().stream().map(this::toActivityDto).collect(Collectors.toList()));
        }
        return dto;
    }

    private CorrectionActivityDto toActivityDto(CorrectionActivity a) {
        CorrectionActivityDto dto = new CorrectionActivityDto();
        dto.setId(a.getId());
        dto.setActionId(a.getActionId());
        dto.setDescripcion(a.getDescripcion());
        dto.setResponsable(a.getResponsable());
        dto.setFechaCompromiso(a.getFechaCompromiso());
        dto.setEstatus(a.getEstatus());
        dto.setObservaciones(a.getObservaciones());
        return dto;
    }
}
