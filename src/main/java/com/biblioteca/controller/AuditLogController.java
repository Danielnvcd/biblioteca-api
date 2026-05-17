package com.biblioteca.controller;

import com.biblioteca.dto.PagedResponse;
import com.biblioteca.model.AuditLog;
import com.biblioteca.repository.AuditLogRepository;
import com.biblioteca.security.Permissions;
import com.biblioteca.security.UserPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/logs")
public class AuditLogController {

    private final AuditLogRepository auditLogRepository;

    public AuditLogController(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @GetMapping
    public ResponseEntity<PagedResponse<AuditLog>> getLogs(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) String q,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        Permissions.requireSuperAdmin(principal);
        String search = (q != null) ? q.trim() : "";
        Page<AuditLog> page = auditLogRepository.search(search, pageable);
        return ResponseEntity.ok(PagedResponse.from(page));
    }
}
