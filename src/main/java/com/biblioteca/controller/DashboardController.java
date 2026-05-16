package com.biblioteca.controller;

import com.biblioteca.dto.DashboardDto;
import com.biblioteca.security.Permissions;
import com.biblioteca.security.UserPrincipal;
import com.biblioteca.service.DashboardService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping
    public ResponseEntity<DashboardDto> getDashboard() {
        return ResponseEntity.ok(dashboardService.getDashboard());
    }

    @PostMapping("/update-dias")
    public ResponseEntity<Map<String, String>> updateDias(@RequestBody(required = false) Map<String, String> body,
                                                          @AuthenticationPrincipal UserPrincipal principal) {
        Permissions.requireAdmin(principal);
        String fecha = (body != null) ? body.get("fecha") : null;
        dashboardService.resetAccidentDate(fecha);
        return ResponseEntity.ok(Map.of("message", "Fecha actualizada"));
    }
}
