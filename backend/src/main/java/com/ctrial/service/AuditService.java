package com.ctrial.service;

import com.ctrial.model.AuditLog;
import com.ctrial.repo.AuditRepo;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class AuditService {

    private final AuditRepo auditRepo;

    public AuditService(AuditRepo auditRepo) {
        this.auditRepo = auditRepo;
    }

    public void record(String actor, String action, String entityType, String entityRef, String detail) {
        auditRepo.save(new AuditLog(actor, action, entityType, entityRef, detail, Instant.now()));
    }

    public List<AuditLog> recent() {
        return auditRepo.findTop200ByOrderByAtDesc();
    }

    public List<AuditLog> forEntity(String type, String ref) {
        return auditRepo.findByEntityTypeAndEntityRefOrderByAtDesc(type, ref);
    }
}
