package com.ctrial.model;

import com.ctrial.model.Enums.DeliveryStatus;
import com.ctrial.model.Enums.Purpose;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A compiled delivery. The compiler pins, at creation time: approved version
 * set, legal member summary, field projection, filter conditions and the
 * per-domain source watermarks used. The summary is frozen and is the audit
 * anchor even after a later withdrawal revokes access to the content.
 */
@Entity
@Table(name = "delivery")
@Getter
@Setter
@NoArgsConstructor
public class Delivery {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String title;

    /** Analysis instant the delivery was compiled at; drives every window check. */
    @Column(name = "analysis_at", nullable = false)
    private Instant analysisAt;

    @Column(nullable = false)
    private int analysisDay;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "run_id")
    private EligibilityRun run;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "approval_id")
    private DataUseApproval approval;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 40)
    private Purpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeliveryStatus status;

    @Column(name = "approved_versions", nullable = false, length = 300)
    private String approvedVersions;

    @Column(name = "field_projection", nullable = false, length = 400)
    private String fieldProjection;

    @Column(name = "member_filter", nullable = false, length = 800)
    private String memberFilter;

    @Column(name = "source_watermarks", nullable = false, length = 2000)
    private String sourceWatermarks;

    @Column(name = "legal_member_count", nullable = false)
    private int legalMemberCount;

    @Column(name = "member_summary", columnDefinition = "text", nullable = false)
    private String memberSummary;

    @Column(name = "legal_member_digest", nullable = false, length = 64)
    private String legalMemberDigest;

    @Column(name = "content_digest", nullable = false, length = 64)
    private String contentDigest;

    @Column(name = "compiled_at", nullable = false)
    private Instant compiledAt;

    @Column(name = "compiled_by", nullable = false)
    private String compiledBy;

    @Column(name = "route", nullable = false, length = 20)
    private String route;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_reason", length = 1000)
    private String revokedReason;

    @Column(name = "access_revoked", nullable = false)
    private boolean accessRevoked;

    @Column(name = "revocation_scope", length = 1000)
    private String revocationScope;

    @Column(name = "revocation_affected_rows")
    private Integer revocationAffectedRows;

    @Column(name = "destroyed_at")
    private Instant destroyedAt;

    /** The content projection is destroyed/cleared, but the summary row survives. */
    @Column(name = "content_cleared", nullable = false)
    private boolean contentCleared;

    @Column(name = "audit_note", length = 2000)
    private String auditNote;
}
