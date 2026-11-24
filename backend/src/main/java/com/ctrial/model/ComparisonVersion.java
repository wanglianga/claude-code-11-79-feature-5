package com.ctrial.model;

import com.ctrial.model.Enums.BridgeRoute;
import com.ctrial.model.Enums.ComparisonStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A published <b>definition-impact comparison version</b>. It is a new,
 * separately watermarked deliverable that compares the two registered
 * definitions; it links to the frozen monthly report but never modifies it.
 *
 * <p>Hard rules enforced by the publisher:
 * <ul>
 *   <li>each route (dual bridge / cohort bridge) produces its own version — a
 *       revised definition can never overwrite the frozen legacy conclusion;</li>
 *   <li>the version carries intervals, not a single recomputed legacy number;</li>
 *   <li>when overlap is insufficient or the uncertainty band is too wide, the
 *       version is published with {@code mergeBlocked=true} and may not feed a
 *       combined efficacy/safety conclusion.</li>
 * </ul>
 */
@Entity
@Table(name = "comparison_version", indexes = {
        @Index(name = "idx_cmp_version_code", columnList = "code", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
public class ComparisonVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "route", nullable = false, length = 20)
    private BridgeRoute route;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ComparisonStatus status;

    /** Link-only reference to the immutable legacy monthly report. */
    @Column(name = "linked_frozen_report_code", nullable = false)
    private String linkedFrozenReportCode;

    @Column(name = "legacy_definition_code", nullable = false)
    private String legacyDefinitionCode;

    @Column(name = "revised_definition_code", nullable = false)
    private String revisedDefinitionCode;

    @Column(name = "watermark_code", nullable = false)
    private String watermarkCode;

    @Column(name = "analysis_at", nullable = false)
    private Instant analysisAt;

    // ---------- figures: legacy side is echoed from the frozen report, never recomputed ----------
    @Column(name = "legacy_qualified", nullable = false)
    private int legacyQualified;

    @Column(name = "legacy_denominator", nullable = false)
    private int legacyDenominator;

    @Column(name = "revised_qualified_point", nullable = false)
    private int revisedQualifiedPoint;

    @Column(name = "revised_qualified_lower", nullable = false)
    private int revisedQualifiedLower;

    @Column(name = "revised_qualified_upper", nullable = false)
    private int revisedQualifiedUpper;

    @Column(name = "revised_denominator_lower", nullable = false)
    private int revisedDenominatorLower;

    @Column(name = "revised_denominator_upper", nullable = false)
    private int revisedDenominatorUpper;

    @Column(name = "window_selection_diff_total", nullable = false)
    private int windowSelectionDiffTotal;

    /** Local-plane legacy encoding minus the frozen submitted legacy qualified count (reported, never written back). */
    @Column(name = "legacy_coverage_delta", nullable = false)
    private int legacyCoverageDelta;

    /** Cohort route only: subjects failing the common-observation-opportunity requirement and therefore excluded. */
    @Column(name = "cohort_excluded_count", nullable = false)
    private int cohortExcludedCount;

    @Column(name = "not_evaluable_total", nullable = false)
    private int notEvaluableTotal;

    @Column(name = "egress_blocked_total", nullable = false)
    private int egressBlockedTotal;

    /** Number of risk-stratum cells whose overlap is below the declared stability floor. */
    @Column(name = "insufficient_overlap_cells", nullable = false)
    private int insufficientOverlapCells;

    @Column(name = "overlap_threshold", nullable = false)
    private double overlapThreshold;

    /** Width of the qualified-count interval relative to the legacy denominator; the merge gate metric. */
    @Column(name = "uncertainty_width_ratio", nullable = false)
    private double uncertaintyWidthRatio;

    @Column(name = "merge_blocked", nullable = false)
    private boolean mergeBlocked;

    @Column(name = "block_reasons", nullable = false, length = 1000)
    private String blockReasons;

    /** Aggregate-only summary lines; no subject-level data is stored on the version. */
    @Column(name = "comparison_summary", columnDefinition = "text", nullable = false)
    private String comparisonSummary;

    @Column(name = "version_digest", nullable = false, length = 64)
    private String versionDigest;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    @Column(name = "published_by", nullable = false)
    private String publishedBy;

    @Column(length = 1000)
    private String note;
}
