package com.ctrial.model;

import com.ctrial.model.Enums.BridgeRoute;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One local dual-definition evaluation pass over the frozen records at a site.
 * Rows ({@link DualDefinitionRow}) are pinned to the run and to its
 * {@link ComparisonWatermark}; the run is the local-plane working material that
 * a publisher later compiles into a cross-border comparison version.
 */
@Entity
@Table(name = "comparison_run", indexes = {
        @Index(name = "idx_cmp_run_code", columnList = "code", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
public class ComparisonRun {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(name = "site_code", nullable = false)
    private String siteCode;

    @Column(name = "frozen_report_code", nullable = false)
    private String frozenReportCode;

    @Column(name = "legacy_definition_code", nullable = false)
    private String legacyDefinitionCode;

    @Column(name = "revised_definition_code", nullable = false)
    private String revisedDefinitionCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "route", nullable = false, length = 20)
    private BridgeRoute route;

    @Column(name = "analysis_at", nullable = false)
    private Instant analysisAt;

    @Column(name = "watermark_code", nullable = false)
    private String watermarkCode;

    @Column(name = "subject_count", nullable = false)
    private int subjectCount;

    @Column(name = "not_evaluable_count", nullable = false)
    private int notEvaluableCount;

    @Column(name = "egress_blocked_count", nullable = false)
    private int egressBlockedCount;

    @Column(name = "set_digest", nullable = false, length = 64)
    private String setDigest;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false)
    private String createdBy;
}
