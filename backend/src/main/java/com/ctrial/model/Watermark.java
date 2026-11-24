package com.ctrial.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Watermark registry, one row per (site, domain). Each row keeps <b>three</b>
 * distinct counters that must never be conflated:
 * <ol>
 *   <li>{@code sourceConfirmedSeq} — highest sequence the site system has
 *       irrevocably published (durable at source).</li>
 *   <li>{@code transferAckSeq} — highest sequence the transport has
 *       acknowledged. May lag source; the difference is data "in flight".</li>
 *   <li>{@code analysisSeq} — highest sequence folded into the global analysis
 *       plane. Nothing above this may be reported as analysed.</li>
 * </ol>
 * A gap between analysisSeq and sourceConfirmedSeq is UNCONFIRMED, never
 * "no events". The local plane is served at sourceConfirmedSeq (source data is
 * already local); the global plane is served at analysisSeq.
 */
@Entity
@Table(name = "watermark", uniqueConstraints = {
        @UniqueConstraint(name = "uq_watermark", columnNames = {"site_code", "domain"})
})
@Getter
@Setter
@NoArgsConstructor
public class Watermark {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_code", nullable = false)
    private String siteCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Enums.Domain domain;

    @Column(name = "source_confirmed_seq", nullable = false)
    private long sourceConfirmedSeq;

    @Column(name = "transfer_ack_seq", nullable = false)
    private long transferAckSeq;

    @Column(name = "analysis_seq", nullable = false)
    private long analysisSeq;

    /** Latest source-side entry instant covered by sourceConfirmedSeq. */
    @Column(name = "source_confirmed_at")
    private Instant sourceConfirmedAt;

    /** Latest occurrence instant represented by analysisSeq. */
    @Column(name = "analysis_at")
    private Instant analysisAt;

    @Column(name = "link_broken_at")
    private Instant linkBrokenAt;

    @Column(name = "link_restored_at")
    private Instant linkRestoredAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public long pendingTransfer() {
        return Math.max(0, sourceConfirmedSeq - transferAckSeq);
    }

    public long pendingAnalysis() {
        return Math.max(0, sourceConfirmedSeq - analysisSeq);
    }

    public boolean linkDown() {
        return linkBrokenAt != null && (linkRestoredAt == null || linkRestoredAt.isBefore(linkBrokenAt));
    }
}
