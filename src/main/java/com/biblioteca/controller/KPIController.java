package com.biblioteca.controller;

import com.biblioteca.model.KPI;
import com.biblioteca.model.Objetivo;
import com.biblioteca.repository.KPIRepository;
import com.biblioteca.repository.ObjetivoRepository;
import com.biblioteca.security.Permissions;
import com.biblioteca.security.UserPrincipal;
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
    public ResponseEntity<Map<String, String>> createKpi(@RequestBody KPI kpi,
                                                         @AuthenticationPrincipal UserPrincipal principal) {
        Permissions.requireAdmin(principal);
        kpiRepository.save(kpi);
        return ResponseEntity.ok(Map.of("message", "KPI creado"));
    }

    @PostMapping("/objetivo/create")
    public ResponseEntity<Map<String, String>> createObjetivo(@RequestBody Objetivo objetivo,
                                                              @AuthenticationPrincipal UserPrincipal principal) {
        Permissions.requireAdmin(principal);
        objetivoRepository.save(objetivo);
        return ResponseEntity.ok(Map.of("message", "Objetivo creado"));
    }

    @PostMapping("/delete/{id}")
    public ResponseEntity<Map<String, String>> deleteKpi(@PathVariable Integer id,
                                                         @AuthenticationPrincipal UserPrincipal principal) {
        Permissions.requireSuperAdmin(principal);
        kpiRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "KPI eliminado"));
    }

    @PostMapping("/objetivo/delete/{id}")
    public ResponseEntity<Map<String, String>> deleteObjetivo(@PathVariable Integer id,
                                                              @AuthenticationPrincipal UserPrincipal principal) {
        Permissions.requireSuperAdmin(principal);
        objetivoRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "Objetivo eliminado"));
    }
}
