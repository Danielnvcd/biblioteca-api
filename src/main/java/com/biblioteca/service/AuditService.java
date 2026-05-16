package com.biblioteca.service;

import com.biblioteca.model.AuditLog;
import com.biblioteca.repository.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AuditService {

    private static final Logger logger = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * Persist an audit entry in its OWN transaction, so failures in the caller's
     * transaction don't lose the log and vice versa.
     * Swallows DB errors — auditing should never break a business operation.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(String user, String action, String ip) {
        try {
            AuditLog log = new AuditLog();
            log.setUser(user);
            log.setAction(action);
            log.setIp(ip);
            log.setCreatedAt(LocalDateTime.now());
            auditLogRepository.save(log);
        } catch (Exception e) {
            logger.warn("Audit log failed for user={} action={}: {}", user, action, e.getMessage());
        }
    }

    public List<AuditLog> getLogs() {
        // Cap to most recent 500 — full table can grow without bound.
        return auditLogRepository.findRecent(PageRequest.of(0, 500));
    }
}
