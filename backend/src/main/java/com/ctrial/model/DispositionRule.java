package com.ctrial.model;

import com.ctrial.model.Enums.DispositionScope;
import com.ctrial.model.Enums.DispositionState;
import com.ctrial.model.Enums.Purpose;
import com.ctrial.model.Enums.RuleAction;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Pre-registered retention / destruction rule. Registered <b>before</b> any
 * withdrawal, so propagation has a deterministic disposition to apply rather
 * than an ad-hoc decision at revocation time.
 */
@Entity
@Table(name = "disposition_rule")
@Getter
@Setter
@NoArgsConstructor
public class DispositionRule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DispositionScope scope;

    /** Optional narrowing to a site. */
    @Column(name = "site_code")
    private String siteCode;

    /** Purpose whose withdrawal triggers this rule; null = any purpose. */
    @Enumerated(EnumType.STRING)
    private Purpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RuleAction action;

    /** For RETAIN_RESTRICTED: minimum retention period in years. */
    @Column(name = "retain_years")
    private Integer retainYears;

    /** For DESTROY_AFTER: days after withdrawal before destruction is due. */
    @Column(name = "destroy_after_days")
    private Integer destroyAfterDays;

    @Column(name = "legal_basis", nullable = false, length = 500)
    private String legalBasis;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt;

    @Column(name = "registered_by", nullable = false)
    private String registeredBy;

    @Column(nullable = false)
    private boolean active = true;

    public boolean matches(String site, Purpose withdrawnPurpose) {
        if (!active) {
            return false;
        }
        if (scope == DispositionScope.SITE && siteCode != null && !siteCode.equalsIgnoreCase(site)) {
            return false;
        }
        return purpose == null || withdrawnPurpose == null || purpose == withdrawnPurpose;
    }
}
