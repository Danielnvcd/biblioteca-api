package com.biblioteca.controller;

import com.biblioteca.model.AuditLog;
import com.biblioteca.security.Permissions;
import com.biblioteca.security.UserPrincipal;
import com.biblioteca.service.AuditService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/logs")
public class AuditLogController {

    private final AuditService auditService;

    public AuditLogController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    public ResponseEntity<List<AuditLog>> getLogs(@AuthenticationPrincipal UserPrincipal principal) {
        Permissions.requireSuperAdmin(principal);
        return ResponseEntity.ok(auditService.getLogs());
    }
}
