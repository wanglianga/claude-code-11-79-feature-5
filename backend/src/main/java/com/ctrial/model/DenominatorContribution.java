package com.ctrial.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Denominator contribution ledger. One row per (site, consent scope, risk
 * stratum) inside a comparison version, holding the range of denominator
 * contribution rather than a single number: exclusion under the revised
 * definition, not-evaluable rows and egress-blocked rows make the exact
 * contribution unknowable from outside the local domain.
 */
@Entity
@Table(name = "denominator_contribution", indexes = {
        @Index(name = "idx_denom_version", columnList = "comparison_version_id"),
        @Index(name = "idx_denom_cell", columnList = "site_code,consent_scope,risk_stratum")
})
@Getter
@Setter
@NoArgsConstructor
public class DenominatorContribution {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "comparison_version_id", nullable = false)
    private Long comparisonVersionId;

    @Column(name = "site_code", nullable = false)
    private String siteCode;

    /** CROSS_BORDER | LOCAL_ONLY — the consent scope the subject group granted. */
    @Column(name = "consent_scope", nullable = false, length = 20)
    private String consentScope;

    /** LOW | MEDIUM | HIGH risk stratum from randomisation. */
    @Column(name = "risk_stratum", nullable = false, length = 20)
    private String riskStratum;

    /** Denominator as submitted in the frozen legacy report, for the same cell where known. */
    @Column(name = "legacy_denominator", nullable = false)
    private int legacyDenominator;

    /** Revised definition's point denominator (settled rows only). */
    @Column(name = "revised_denominator_point", nullable = false)
    private int revisedDenominatorPoint;

    @Column(name = "denominator_lower", nullable = false)
    private int denominatorLower;

    @Column(name = "denominator_upper", nullable = false)
    private int denominatorUpper;

    /** Overlap fraction [0,1] observed in this cell; below the stability floor marks the cell insufficient. */
    @Column(name = "overlap_fraction", nullable = false)
    private double overlapFraction;

    @Column(name = "overlap_insufficient", nullable = false)
    private boolean overlapInsufficient;

    /** Cohort-route standardisation weight applied to the old risk-stratum mix; 1.0 for the dual bridge. */
    @Column(name = "standardisation_weight", nullable = false)
    private double standardisationWeight;

    @Column(length = 1000)
    private String note;
}
