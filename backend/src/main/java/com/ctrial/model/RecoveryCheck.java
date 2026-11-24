package com.ctrial.model;

import com.ctrial.model.Enums.RecoveryStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Result of a post-outage recovery check: gaps, duplicate re-sends, cross-domain digest agreement. */
@Entity
@Table(name = "recovery_check")
@Getter
@Setter
@NoArgsConstructor
public class RecoveryCheck {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(name = "site_code", nullable = false)
    private String siteCode;

    @Column(name = "run_at", nullable = false)
    private Instant runAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RecoveryStatus status;

    @Column(name = "gap_count", nullable = false)
    private int gapCount;

    @Column(name = "duplicate_count", nullable = false)
    private int duplicateCount;

    @Column(name = "mismatch_count", nullable = false)
    private int mismatchCount;

    @Column(name = "report_json", columnDefinition = "text", nullable = false)
    private String reportJson;

    @Column(name = "report_digest", nullable = false, length = 64)
    private String reportDigest;

    @Column(name = "run_by", nullable = false)
    private String runBy;
}
