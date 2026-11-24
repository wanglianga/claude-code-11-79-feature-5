package com.ctrial.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A local endpoint test assessment (efficacy/safety laboratory readout plus,
 * where required, a medical adjudication). These records exist only in the
 * local plane; the dual-definition evaluator reads them at the site and emits
 * aggregate counts, never the rows themselves, across the border.
 *
 * <p>Three instants are kept separate, mirroring the consent ledger's
 * discipline: {@code occurredAt} (test occurrence), {@code receivedAt} (source
 * receipt) and {@code adjudicatedAt} (medical adjudication, possibly null and
 * possibly later than a frozen watermark).
 */
@Entity
@Table(name = "endpoint_assessment", uniqueConstraints = {
        @UniqueConstraint(name = "uq_endpoint_stable", columnNames = {"stable_id", "revision"})
}, indexes = {
        @Index(name = "idx_endpoint_subject", columnList = "subject_pseudonym"),
        @Index(name = "idx_endpoint_week", columnList = "study_week")
})
@Getter
@Setter
@NoArgsConstructor
public class EndpointAssessment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stable_id", nullable = false)
    private String stableId;

    @Column(nullable = false)
    private int revision = 1;

    @Column(name = "subject_pseudonym", nullable = false)
    private String subjectPseudonym;

    @Column(name = "site_code", nullable = false)
    private String siteCode;

    /** EFFICACY | SAFETY. */
    @Column(name = "endpoint_kind", nullable = false, length = 20)
    private String endpointKind;

    /** Post-randomisation study week of the test occurrence. */
    @Column(name = "study_week", nullable = false)
    private int studyWeek;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    /** Local signature time of the medical adjudication; null while adjudication is outstanding. */
    @Column(name = "adjudicated_at")
    private Instant adjudicatedAt;

    /** Source sequence in the local domain ledger. */
    @Column(name = "source_seq", nullable = false)
    private long sourceSeq;

    @Column(name = "qualifying_result", nullable = false)
    private boolean qualifyingResult;

    /** Risk stratum assigned at randomisation: LOW | MEDIUM | HIGH. */
    @Column(name = "risk_stratum", nullable = false, length = 20)
    private String riskStratum;

    @Column(length = 500)
    private String note;

    public boolean adjudicatedAtOrBefore(Instant instant) {
        return adjudicatedAt != null && !adjudicatedAt.isAfter(instant);
    }
}
