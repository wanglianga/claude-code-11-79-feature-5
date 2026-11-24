package com.ctrial.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * An analyst annotation on an aggregate difference inside the attribution
 * workbench. Annotations attach to aggregate cells (site × endpoint ×
 * reclassification kind); they never alter the comparison version itself,
 * which remains append-only once published.
 */
@Entity
@Table(name = "difference_annotation", indexes = {
        @Index(name = "idx_annotation_version", columnList = "comparison_version_id")
})
@Getter
@Setter
@NoArgsConstructor
public class DifferenceAnnotation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "comparison_version_id", nullable = false)
    private Long comparisonVersionId;

    @Column(name = "site_code", nullable = false)
    private String siteCode;

    @Column(name = "endpoint_kind", nullable = false, length = 20)
    private String endpointKind;

    /** RETAINED | NEWLY_QUALIFIED | LOST | INDETERMINATE | WINDOW_SELECTION | OVERLAP | EGRESS. */
    @Column(name = "difference_kind", nullable = false, length = 30)
    private String differenceKind;

    @Column(nullable = false, length = 1000)
    private String explanation;

    @Column(name = "attributed_to", nullable = false, length = 200)
    private String attributedTo;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false)
    private String createdBy;
}
