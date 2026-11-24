package com.ctrial.model;

import com.ctrial.model.Enums.Domain;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Local event ledger (source of truth). One row per accepted transmission.
 *
 * <p>Deduplication evidence: {@code (sourceSystem, sourceSeq)} is the unique
 * transport identity; {@code (stableId, revision)} is the clinical identity.
 * A retransmission of the same event after a link outage must resolve to the
 * same row rather than creating a second count.
 *
 * <p>No direct identifiers live here. {@code subject.pseudonym} is the only
 * link to a person and is itself already pseudonymised at source.
 *
 * <p>Corrections do not overwrite: a corrected event is a new row whose
 * {@code revision} increments and whose {@code supersedesEventId} points at the
 * row it replaces. The superseded row remains visible for audit.
 */
@Entity
@Table(name = "local_event", uniqueConstraints = {
        @UniqueConstraint(name = "uq_event_transport", columnNames = {"source_system", "domain", "source_seq"}),
        @UniqueConstraint(name = "uq_event_clinical", columnNames = {"stable_id", "revision"})
}, indexes = {
        @Index(name = "idx_event_subject_domain", columnList = "subject_id,domain"),
        @Index(name = "idx_event_occurred", columnList = "occurred_at")
})
@Getter
@Setter
@NoArgsConstructor
public class LocalEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stable clinical identity, e.g. "VST-S001-V2", "AE-S002-01". Never recycled. */
    @Column(name = "stable_id", nullable = false)
    private String stableId;

    @Column(nullable = false)
    private int revision = 1;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "subject_id")
    private Subject subject;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Domain domain;

    /** Clinical occurrence instant — the time used in windowed eligibility. */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** Time the source site system accepted the record (may precede re-upload). */
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    /** Time the platform ingested it over the wire. */
    @Column(name = "ingested_at", nullable = false)
    private Instant ingestedAt;

    /** Transport identity per source system. */
    @Column(name = "source_system", nullable = false)
    private String sourceSystem;

    @Column(name = "source_seq", nullable = false)
    private long sourceSeq;

    @Column(name = "supersedes_event_id")
    private Long supersedesEventId;

    @Column(name = "protocol_version", nullable = false)
    private String protocolVersion;

    /** Fields actually present in the payload, e.g. "DEMOG,LABS". */
    @Column(nullable = false, length = 500)
    private String fieldGroups;

    /**
     * Canonical field-value payload. Derived-only; contains no direct identifiers.
     * The delivery compiler projects a subset of these fields.
     */
    @Column(columnDefinition = "text")
    private String payloadJson;

    @Column(nullable = false)
    private boolean voided;

    @Column(name = "voided_at")
    private Instant voidedAt;

    @Column(name = "void_reason", length = 500)
    private String voidReason;

    /** Hash of (stableId|revision|domain|occurredAt|payload) used for duplicate-evidence checks. */
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    public String naturalKey() {
        return stableId + "#" + revision;
    }
}
