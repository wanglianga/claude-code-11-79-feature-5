package com.ctrial.model;

import com.ctrial.model.Enums.EgressBlockReason;
import com.ctrial.model.Enums.EndpointQualification;
import com.ctrial.model.Enums.ReclassificationKind;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One subject's dual-definition encoding on the same frozen record set. Both
 * definitions are evaluated locally against identical inputs; the row keeps
 * the full reason trace per definition plus the egress verdict. Rows of
 * China-resident sites are retained in the local plane only — the global
 * workbench reads them solely through an explicit local-plane authorisation
 * and they never leave the region inside a comparison version.
 */
@Entity
@Table(name = "dual_definition_row", indexes = {
        @Index(name = "idx_ddr_run_subject", columnList = "comparison_run_id,subject_pseudonym", unique = true),
        @Index(name = "idx_ddr_site", columnList = "site_code")
})
@Getter
@Setter
@NoArgsConstructor
public class DualDefinitionRow {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "comparison_run_id", nullable = false)
    private Long comparisonRunId;

    @Column(name = "subject_pseudonym", nullable = false)
    private String subjectPseudonym;

    @Column(name = "site_code", nullable = false)
    private String siteCode;

    @Column(name = "region", nullable = false, length = 10)
    private String region;

    @Column(name = "risk_stratum", nullable = false, length = 20)
    private String riskStratum;

    @Column(name = "china_resident", nullable = false)
    private boolean chinaResident;

    // ---------- legacy definition encoding ----------
    @Enumerated(EnumType.STRING)
    @Column(name = "legacy_qualification", nullable = false, length = 30)
    private EndpointQualification legacyQualification;

    @Column(name = "legacy_reasons", nullable = false, length = 1000)
    private String legacyReasons;

    /** Stable id of the test the legacy definition selected; null when none. */
    @Column(name = "legacy_selected_test", length = 60)
    private String legacySelectedTest;

    @Column(name = "legacy_selected_week")
    private Integer legacySelectedWeek;

    // ---------- revised definition encoding ----------
    @Enumerated(EnumType.STRING)
    @Column(name = "revised_qualification", nullable = false, length = 30)
    private EndpointQualification revisedQualification;

    @Column(name = "revised_reasons", nullable = false, length = 1000)
    private String revisedReasons;

    @Column(name = "revised_selected_test", length = 60)
    private String revisedSelectedTest;

    @Column(name = "revised_selected_week")
    private Integer revisedSelectedWeek;

    /** Distance in weeks between the revised choice and the target week; null when no choice. */
    @Column(name = "revised_distance_weeks")
    private Integer revisedDistanceWeeks;

    /** True when both definitions select a test but the revised window picks a different one. */
    @Column(name = "window_selection_differs", nullable = false)
    private boolean windowSelectionDiffers;

    // ---------- local-plane / egress encoding ----------
    @Enumerated(EnumType.STRING)
    @Column(name = "egress_block", nullable = false, length = 40)
    private EgressBlockReason egressBlock;

    @Column(name = "egress_reasons", nullable = false, length = 1000)
    private String egressReasons;

    @Column(name = "not_evaluable", nullable = false)
    private boolean notEvaluable;

    /** True only when the subject has both a week-12 test and a separate in-window qualifying test. */
    @Column(name = "dual_observation_opportunity", nullable = false)
    private boolean dualObservationOpportunity;

    /** True when the subject satisfies the pre-declared common-observation-opportunity cohort criteria. */
    @Column(name = "common_cohort_member", nullable = false)
    private boolean commonCohortMember;

    @Enumerated(EnumType.STRING)
    @Column(name = "reclassification", nullable = false, length = 20)
    private ReclassificationKind reclassification;

    @Column(name = "evaluated_at", nullable = false)
    private Instant evaluatedAt;

    @Column(name = "row_hash", nullable = false, length = 64)
    private String rowHash;
}
