package com.ctrial.model;

import com.ctrial.common.Text;
import com.ctrial.model.Enums.ProtocolStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Set;

/**
 * Protocol catalogue. A version only governs visits from its explicit
 * {@code effectiveFrom} onwards, and only once ethics + sponsor approval have
 * both landed. Until then candidates evaluated against this version stay
 * PENDING rather than falling through to a previous version's rules.
 */
@Entity
@Table(name = "protocol_version", indexes = {
        @Index(name = "idx_protocol_version", columnList = "version", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
public class ProtocolVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String version;

    @Column(nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProtocolStatus status;

    @Column(name = "ethics_approved_at")
    private Instant ethicsApprovedAt;

    @Column(name = "sponsor_approved_at")
    private Instant sponsorApprovedAt;

    /** Explicit applicability instant for subsequent visits. Not the approval instant. */
    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    /** Sites this version applies to; empty means all sites. */
    @Column(nullable = false, length = 400)
    private String applicableSites;

    /** Field groups this version newly requires / adds for its own scope. */
    @Column(nullable = false, length = 500)
    private String requiredFieldGroups;

    /** Parent version, if this is an amendment. */
    @Column(name = "amends_version")
    private String amendsVersion;

    @Column(length = 1000)
    private String note;

    public boolean fullyApproved() {
        return ethicsApprovedAt != null && sponsorApprovedAt != null
                && (status == ProtocolStatus.APPROVED || status == ProtocolStatus.PARTIALLY_APPROVED);
    }

    public boolean approvedAt(Instant instant) {
        return fullyApproved()
                && ethicsApprovedAt.isBefore(instant.plusNanos(1))
                && sponsorApprovedAt.isBefore(instant.plusNanos(1));
    }

    public boolean appliesToSite(String siteCode) {
        Set<String> sites = Text.stringSet(applicableSites);
        return sites.isEmpty() || sites.contains(siteCode.toUpperCase());
    }

    public boolean activeAt(Instant instant) {
        return approvedAt(instant)
                && !effectiveFrom.isAfter(instant)
                && (effectiveTo == null || effectiveTo.isAfter(instant));
    }

    public Set<String> requiredFieldGroupSet() {
        return Text.stringSet(requiredFieldGroups);
    }
}
