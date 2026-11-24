package com.ctrial.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A monthly report already submitted under the legacy frozen analysis plane.
 * Rows are created once and are immutable by construction: no service or
 * endpoint in this application updates or deletes them. A comparison version
 * may link to this baseline but must never rewrite, replace or recompute it.
 */
@Entity
@Table(name = "frozen_monthly_report", indexes = {
        @Index(name = "idx_fmr_code", columnList = "code", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
public class FrozenMonthlyReport {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String title;

    /** Reporting period label, e.g. "2026-M05". */
    @Column(name = "period_label", nullable = false)
    private String periodLabel;

    /** When the report was submitted to the global statistics team. */
    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "frozen_at", nullable = false)
    private Instant frozenAt;

    @Column(name = "frozen_by", nullable = false)
    private String frozenBy;

    /** Analysis instant the submitted figures reflect. */
    @Column(name = "analysis_at", nullable = false)
    private Instant analysisAt;

    @Column(name = "legacy_definition_code", nullable = false)
    private String legacyDefinitionCode;

    /** Per-site/domain watermark pin string captured at freeze time. */
    @Column(name = "pinned_watermark", nullable = false, length = 2000)
    private String pinnedWatermark;

    /** Risk-stratum denominator mix as submitted, "LOW=n,MEDIUM=n,HIGH=n". */
    @Column(name = "stratum_denominators", nullable = false, length = 500)
    private String stratumDenominators;

    @Column(name = "legacy_denominator_total", nullable = false)
    private int legacyDenominatorTotal;

    @Column(name = "legacy_qualified_total", nullable = false)
    private int legacyQualifiedTotal;

    /** Aggregate-only frozen summary lines (site:stratum:qualified:denominator); no identity leaves the plane. */
    @Column(name = "member_summary", columnDefinition = "text", nullable = false)
    private String memberSummary;

    @Column(name = "report_digest", nullable = false, length = 64)
    private String reportDigest;

    /** Always true once persisted; the guard exists so application code cannot unfreeze a baseline. */
    @Column(nullable = false)
    private boolean immutable = true;

    @Column(length = 1000)
    private String note;
}
