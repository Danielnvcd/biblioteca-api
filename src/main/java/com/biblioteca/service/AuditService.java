package com.biblioteca.service;

import com.biblioteca.model.AuditLog;
import com.biblioteca.repository.AuditLogRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    public void log(String user, String action, String ip) {
        AuditLog log = new AuditLog();
        log.setUser(user);
        log.setAction(action);
        log.setIp(ip);
        log.setCreatedAt(LocalDateTime.now());
        auditLogRepository.save(log);
    }

    public List<AuditLog> getLogs() {
        return auditLogRepository.findAllByOrderByCreatedAtDesc();
    }
}
