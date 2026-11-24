package com.ctrial.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Append-only audit trail for every state transition that matters to a regulator. */
@Entity
@Table(name = "audit_log", indexes = {
        @Index(name = "idx_audit_at", columnList = "at")
})
@Getter
@Setter
@NoArgsConstructor
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant at;

    @Column(nullable = false)
    private String actor;

    @Column(nullable = false)
    private String action;

    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Column(name = "entity_ref")
    private String entityRef;

    @Column(length = 2000)
    private String detail;

    public AuditLog(String actor, String action, String entityType, String entityRef, String detail, Instant at) {
        this.actor = actor;
        this.action = action;
        this.entityType = entityType;
        this.entityRef = entityRef;
        this.detail = detail;
        this.at = at;
    }
}
