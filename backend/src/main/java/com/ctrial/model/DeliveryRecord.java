package com.ctrial.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A projected, de-identified record inside a delivery. Only fields allowed by
 * the approval's field scope are present; identity columns are excluded by
 * construction. On revocation, this content may be restricted or cleared, but
 * the delivery summary and the audit trail remain.
 */
@Entity
@Table(name = "delivery_record", indexes = {
        @Index(name = "idx_delivery_record", columnList = "delivery_id"),
        @Index(name = "idx_delivery_record_pseudonym", columnList = "subject_pseudonym")
})
@Getter
@Setter
@NoArgsConstructor
public class DeliveryRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "delivery_id")
    private Delivery delivery;

    @Column(name = "subject_pseudonym", nullable = false)
    private String subjectPseudonym;

    @Column(name = "site_code", nullable = false)
    private String siteCode;

    @Column(nullable = false)
    private String region;

    @Column(nullable = false)
    private String domain;

    @Column(name = "stable_id", nullable = false)
    private String stableId;

    @Column(nullable = false)
    private int revision;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "protocol_version", nullable = false)
    private String protocolVersion;

    /** Only the projected fields; never identity fields. */
    @Column(name = "projected_json", columnDefinition = "text")
    private String projectedJson;

    @Column(name = "record_hash", nullable = false, length = 64)
    private String recordHash;

    @Column(name = "access_revoked", nullable = false)
    private boolean accessRevoked;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "cleared", nullable = false)
    private boolean cleared;
}
