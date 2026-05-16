package com.biblioteca.controller;

import com.biblioteca.dto.KpiDto;
import com.biblioteca.dto.ObjetivoDto;
import com.biblioteca.exception.ApiException;
import com.biblioteca.model.KPI;
import com.biblioteca.model.Objetivo;
import com.biblioteca.repository.KPIRepository;
import com.biblioteca.repository.ObjetivoRepository;
import com.biblioteca.security.Permissions;
import com.biblioteca.security.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/kpis")
public class KPIController {

    private final KPIRepository kpiRepository;
    private final ObjetivoRepository objetivoRepository;

    public KPIController(KPIRepository kpiRepository, ObjetivoRepository objetivoRepository) {
        this.kpiRepository = kpiRepository;
        this.objetivoRepository = objetivoRepository;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list() {
        Map<String, Object> response = Map.of(
            "kpis", kpiRepository.findAll(),
            "objetivos", objetivoRepository.findAll()
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/create")
    public ResponseEntity<Map<String, String>> createKpi(@Valid @RequestBody KpiDto dto,
                                                         @AuthenticationPrincipal UserPrincipal principal) {
        Permissions.requireAdmin(principal);
        KPI kpi = new KPI();
        kpi.setNombre(dto.getNombre());
        kpi.setValorActual(dto.getValorActual());
        kpi.setMeta(dto.getMeta());
        kpi.setUnidad(dto.getUnidad());
        kpi.setEstado(dto.getEstado() != null ? dto.getEstado() : "ok");
        kpiRepository.save(kpi);
        return ResponseEntity.ok(Map.of("message", "KPI creado"));
    }

    @PostMapping("/objetivo/create")
    public ResponseEntity<Map<String, String>> createObjetivo(@Valid @RequestBody ObjetivoDto dto,
                                                              @AuthenticationPrincipal UserPrincipal principal) {
        Permissions.requireAdmin(principal);
        Objetivo o = new Objetivo();
        o.setTitulo(dto.getTitulo());
        o.setProcesoLider(dto.getProcesoLider());
        o.setProcesosInvolucrados(dto.getProcesosInvolucrados());
        o.setMetaDesc(dto.getMetaDesc());
        o.setResultadoActual(dto.getResultadoActual());
        o.setEstado(dto.getEstado() != null ? dto.getEstado() : "en_meta");
        objetivoRepository.save(o);
        return ResponseEntity.ok(Map.of("message", "Objetivo creado"));
    }

    @PostMapping("/delete/{id}")
    public ResponseEntity<Map<String, String>> deleteKpi(@PathVariable Integer id,
                                                         @AuthenticationPrincipal UserPrincipal principal) {
        Permissions.requireSuperAdmin(principal);
        if (!kpiRepository.existsById(id)) throw ApiException.notFound("KPI no encontrado");
        kpiRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "KPI eliminado"));
    }

    @PostMapping("/objetivo/delete/{id}")
    public ResponseEntity<Map<String, String>> deleteObjetivo(@PathVariable Integer id,
                                                              @AuthenticationPrincipal UserPrincipal principal) {
        Permissions.requireSuperAdmin(principal);
        if (!objetivoRepository.existsById(id)) throw ApiException.notFound("Objetivo no encontrado");
        objetivoRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "Objetivo eliminado"));
    }
}
