package com.ctrial.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A protocol deviation recorded in the local plane. Under the revised
 * definition a class-1 deviation is an exclusion condition (the subject leaves
 * the endpoint denominator); under the legacy definition it remains only a
 * flag. Class 2/3 deviations never exclude.
 */
@Entity
@Table(name = "protocol_deviation_entry", indexes = {
        @Index(name = "idx_deviation_subject", columnList = "subject_pseudonym")
})
@Getter
@Setter
@NoArgsConstructor
public class ProtocolDeviationEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stable_id", nullable = false, unique = true)
    private String stableId;

    @Column(name = "subject_pseudonym", nullable = false)
    private String subjectPseudonym;

    @Column(name = "site_code", nullable = false)
    private String siteCode;

    /** 1 = class-1 (exclusion under the revised definition); 2/3 are documented but not excluding. */
    @Column(name = "deviation_class", nullable = false)
    private int deviationClass;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(length = 500)
    private String note;

    public boolean isClassOne() {
        return deviationClass == 1;
    }
}
