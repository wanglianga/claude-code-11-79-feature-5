package com.ctrial.model;

import com.ctrial.model.Enums.Eligibility;
import com.ctrial.model.Enums.PlaneType;
import com.ctrial.model.Enums.Verdict;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A single persisted eligibility row of a MATERIALIZED run. Every row carries
 * its full reason trace so the audit screen can explain a decision without
 * recomputation.
 */
@Entity
@Table(name = "eligibility_row", indexes = {
        @Index(name = "idx_row_run", columnList = "run_id"),
        @Index(name = "idx_row_subject", columnList = "subject_pseudonym")
})
@Getter
@Setter
@NoArgsConstructor
public class EligibilityRow {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id")
    private EligibilityRun run;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "event_id")
    private LocalEvent event;

    @Column(name = "subject_pseudonym", nullable = false)
    private String subjectPseudonym;

    @Column(name = "site_code", nullable = false)
    private String siteCode;

    @Column(nullable = false)
    private String domain;

    @Column(name = "stable_id", nullable = false)
    private String stableId;

    @Column(nullable = false)
    private int revision;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(nullable = false)
    private int localDay;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Eligibility eligibility;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Verdict verdict;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlaneType plane;

    @Column(name = "reason_codes", nullable = false, length = 800)
    private String reasonCodes;

    @Column(nullable = false, length = 2000)
    private String reasons;

    @Column(name = "protocol_version", nullable = false)
    private String protocolVersion;

    @Column(name = "projected_fields", nullable = false, length = 500)
    private String projectedFields;

    @Column(name = "watermark_gap", nullable = false)
    private boolean watermarkGap;

    @Column(name = "row_hash", nullable = false, length = 64)
    private String rowHash;
}
