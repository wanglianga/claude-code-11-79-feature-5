package com.ctrial.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * The only thing the local domain may emit across the border: an approved
 * aggregate. No subject-level identity, pseudonym or payload appears here —
 * reclassification counts, window-selection difference counts and the
 * uncertainty interval are all that leave the site.
 *
 * <p>The uncertainty interval exists precisely because the two routes cannot
 * resolve every subject deterministically: not-evaluable rows, egress-blocked
 * rows and (for the dual bridge) insufficient overlap widen the range.
 */
@Entity
@Table(name = "local_bridge_aggregate", indexes = {
        @Index(name = "idx_bridge_comparison", columnList = "comparison_version_id"),
        @Index(name = "idx_bridge_site", columnList = "site_code,endpoint_kind")
})
@Getter
@Setter
@NoArgsConstructor
public class LocalBridgeAggregate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "comparison_version_id")
    private Long comparisonVersionId;

    @Column(name = "comparison_run_id", nullable = false)
    private Long comparisonRunId;

    @Column(name = "site_code", nullable = false)
    private String siteCode;

    @Column(name = "endpoint_kind", nullable = false, length = 20)
    private String endpointKind;

    @Column(name = "retained_count", nullable = false)
    private int retainedCount;

    @Column(name = "newly_qualified_count", nullable = false)
    private int newlyQualifiedCount;

    @Column(name = "lost_count", nullable = false)
    private int lostCount;

    @Column(name = "never_qualified_count", nullable = false)
    private int neverQualifiedCount;

    @Column(name = "indeterminate_count", nullable = false)
    private int indeterminateCount;

    /** Subjects where the revised window selected a different test than the week-12 rule. */
    @Column(name = "window_selection_diff_count", nullable = false)
    private int windowSelectionDiffCount;

    /** Lower/upper bounds of the revised qualified count given indeterminate and egress-blocked rows. */
    @Column(name = "uncertainty_lower", nullable = false)
    private int uncertaintyLower;

    @Column(name = "uncertainty_upper", nullable = false)
    private int uncertaintyUpper;

    /** Lower/upper bounds of the revised denominator (exclusion + indeterminate membership). */
    @Column(name = "denominator_lower", nullable = false)
    private int denominatorLower;

    @Column(name = "denominator_upper", nullable = false)
    private int denominatorUpper;

    @Column(name = "egress_blocked_count", nullable = false)
    private int egressBlockedCount;

    @Column(name = "aggregate_digest", nullable = false, length = 64)
    private String aggregateDigest;

    @Column(name = "emitted_at", nullable = false)
    private Instant emittedAt;
}
