package com.ctrial.model;

import com.ctrial.model.Enums.DefinitionKey;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Analysis definition registry. Exactly two rows are ever registered and they
 * are locked: the legacy week-12 rule and the revised weeks-10–14 rule, each
 * with its fixed applicability interval. A comparison version references these
 * rows; it can never edit them or register a replacement definition.
 */
@Entity
@Table(name = "analysis_definition", indexes = {
        @Index(name = "idx_definition_key", columnList = "definition_key", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
public class AnalysisDefinition {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "definition_key", nullable = false, length = 40)
    private DefinitionKey definitionKey;

    @Column(nullable = false)
    private String title;

    /** Semantic version of the rule text, e.g. "legacy-1.0" / "revised-2.0". */
    @Column(name = "definition_version", nullable = false)
    private String definitionVersion;

    /** Target post-randomisation week of the endpoint test (12 for both definitions). */
    @Column(name = "target_week", nullable = false)
    private int targetWeek;

    /** Inclusive lower/upper accepted study weeks. Legacy pins both to 12; revised uses 10..14. */
    @Column(name = "window_start_week", nullable = false)
    private int windowStartWeek;

    @Column(name = "window_end_week", nullable = false)
    private int windowEndWeek;

    /** Revised definition picks the qualifying test closest to the target week; legacy requires an exact hit. */
    @Column(name = "closest_to_target", nullable = false)
    private boolean closestToTarget;

    /** A class-1 protocol deviation is an exclusion only under the revised definition. */
    @Column(name = "deviation_class1_exclusion", nullable = false)
    private boolean deviationClass1Exclusion;

    /** Fixed applicability interval. Half-open [applicableFrom, applicableTo). */
    @Column(name = "applicable_from", nullable = false)
    private Instant applicableFrom;

    @Column(name = "applicable_to", nullable = false)
    private Instant applicableTo;

    /** Canonical hash of the rule parameters; the audit anchor of the registry row. */
    @Column(name = "rules_digest", nullable = false, length = 64)
    private String rulesDigest;

    @Column(nullable = false)
    private boolean locked;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt;

    @Column(name = "registered_by", nullable = false)
    private String registeredBy;

    @Column(length = 1000)
    private String note;
}
