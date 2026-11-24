package com.ctrial.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Append-only log of every transmission attempt (one row per attempt, including
 * duplicates), so the recovery check can count re-sends without collapsing them.
 */
@Entity
@Table(name = "transport_receipt", indexes = {
        @Index(name = "idx_receipt_transport", columnList = "source_system,domain,source_seq")
})
@Getter
@Setter
@NoArgsConstructor
public class TransportReceipt {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_system", nullable = false)
    private String sourceSystem;

    @Column(nullable = false)
    private String domain;

    @Column(name = "source_seq", nullable = false)
    private long sourceSeq;

    @Column(name = "stable_id", nullable = false)
    private String stableId;

    @Column(nullable = false)
    private int revision;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "acked_at", nullable = false)
    private Instant ackedAt;

    @Column(name = "was_duplicate", nullable = false)
    private boolean wasDuplicate;

    @Column(name = "event_id")
    private Long eventId;
}
