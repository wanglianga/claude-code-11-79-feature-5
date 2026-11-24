package com.ctrial.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One eligibility evaluation pass over the candidate records at a single
 * analysis instant. This is the "materialised snapshot" of the alternative
 * route: rows are pinned, and later consent/protocol revisions mark it STALE.
 *
 * <p>A run can also be produced transiently by the replay route; in that case
 * {@code route=REPLAY} and rows are not persisted (the returned comparison
 * object is). Both routes call the same engine with the same rules.
 */
@Entity
@Table(name = "eligibility_run", uniqueConstraints = {
        @UniqueConstraint(name = "uq_run_code", columnNames = {"code"})
})
@Getter
@Setter
@NoArgsConstructor
public class EligibilityRun {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    /** Envelope governing the GLOBAL plane at this instant; null if none was active. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "approval_id")
    private DataUseApproval approval;

    @Column(name = "analysis_at", nullable = false)
    private Instant analysisAt;

    @Column(nullable = false)
    private int analysisDay;

    /** MATERIALIZED | REPLAY */
    @Column(nullable = false, length = 20)
    private String route;

    /** VALID | STALE | AUDIT_ONLY */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Enums.SnapshotStatus status;

    /** Per-site/domain watermarks captured for this run, "SITE|DOMAIN|src|ack|ana;..." */
    @Column(name = "watermarks", nullable = false, length = 2000)
    private String watermarks;

    @Column(name = "completeness_ok", nullable = false)
    private boolean completenessOk;

    @Column(name = "row_count", nullable = false)
    private int rowCount;

    @Column(name = "global_count", nullable = false)
    private int globalCount;

    @Column(name = "local_count", nullable = false)
    private int localCount;

    @Column(name = "pending_count", nullable = false)
    private int pendingCount;

    @Column(name = "set_digest", nullable = false, length = 64)
    private String setDigest;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "stale_reason", length = 1000)
    private String staleReason;
}
