package com.ctrial.model;

import com.ctrial.common.Text;
import com.ctrial.model.Enums.ConsentKind;
import com.ctrial.model.Enums.Purpose;
import com.ctrial.model.Enums.Region;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Set;

/**
 * Append-only consent ledger. One row per signature (grant or withdrawal).
 *
 * <p>Two clocks are kept deliberately separate:
 * <ul>
 *   <li>{@code effectiveFrom} / {@code effectiveTo}: the <b>local</b> signature
 *       or withdrawal time from the paper/e-signature at the site. All
 *       eligibility decisions use these.</li>
 *   <li>{@code recordedAt}: the instant the platform received the entry. It is
 *       never used as the consent validity boundary, only for ordering records
 *       that share an effective time.</li>
 * </ul>
 * Effective intervals are half-open {@code [effectiveFrom, effectiveTo)}.
 */
@Entity
@Table(name = "consent_entry", indexes = {
        @Index(name = "idx_consent_subject_time", columnList = "subject_id,effective_from")
})
@Getter
@Setter
@NoArgsConstructor
public class ConsentEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "subject_id")
    private Subject subject;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConsentKind kind;

    /** Concrete purposes covered by this entry; ALL is permitted. */
    @Column(nullable = false, length = 500)
    private String purposes;

    /** Field groups granted (ignored for withdrawals, which revoke whole dimensions). */
    @Column(nullable = false, length = 500)
    private String fieldGroups;

    /** Regions this entry speaks to; GLOBAL means "any destination". */
    @Column(nullable = false, length = 200)
    private String regions;

    /** Local signature / withdrawal instant. The only validity boundary. */
    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    /** Optional explicit local expiry; null = open-ended until withdrawn. */
    @Column(name = "effective_to")
    private Instant effectiveTo;

    /** Platform receipt instant; ordering tie-breaker only. */
    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "document_ref")
    private String documentRef;

    @Column(length = 1000)
    private String note;

    /** When set, a withdrawal that was itself corrected by a later signed entry. */
    @Column(name = "supersedes_entry_id")
    private Long supersedesEntryId;

    public Set<Purpose> purposeSet() {
        return Text.enumSet(purposes, Purpose.class);
    }

    public Set<String> fieldGroupSet() {
        return Text.stringSet(fieldGroups);
    }

    public Set<Region> regionSet() {
        return Text.enumSet(regions, Region.class);
    }

    public String getPurposeLabelForAudit() {
        return "purpose(s) " + purposes + " for region(s) " + regions;
    }

    public boolean covers(Purpose requested, String region) {
        Set<Purpose> ps = purposeSet();
        boolean purposeHit = ps.contains(Purpose.ALL) || ps.contains(requested);
        if (!purposeHit) {
            return false;
        }
        return regionSet().stream().anyMatch(r -> r.covers(region));
    }
}
