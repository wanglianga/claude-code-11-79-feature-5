package com.ctrial.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A watermark owned exclusively by a definition-impact comparison. It is a
 * separate counter plane from the source/transfer/analysis {@link Watermark}
 * ledger and from the pin embedded in a {@link FrozenMonthlyReport}: each
 * comparison evaluation captures its own row, so advancing it can never move
 * the legacy monthly report's baseline.
 */
@Entity
@Table(name = "comparison_watermark", indexes = {
        @Index(name = "idx_cmp_wm_code", columnList = "code", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
public class ComparisonWatermark {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String label;

    /** "SITE|DOMAIN|sourceConfirmed;..." pin captured for this comparison only. */
    @Column(name = "pin", nullable = false, length = 2000)
    private String pin;

    @Column(nullable = false, length = 64)
    private String digest;

    /** Legacy baseline this pin was captured against, by code (link only; the baseline is never modified). */
    @Column(name = "linked_frozen_report_code")
    private String linkedFrozenReportCode;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    @Column(name = "captured_by", nullable = false)
    private String capturedBy;

    @Column(length = 1000)
    private String note;
}
