package com.ctrial.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Subject identity is never transmitted. The local ledger stores only the
 * pseudonym plus a site-scoped identity handle; the derived global plane sees
 * the pseudonym and a region label, never a name, MRN or any source identifier.
 */
@Entity
@Table(name = "subject", indexes = {
        @Index(name = "idx_subject_pseudonym", columnList = "pseudonym", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
public class Subject {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String pseudonym;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "site_id")
    private Site site;

    @Column(nullable = false)
    private String region;

    /**
     * Local-only direct identifier (e.g. CHN-001-0007). Never projected into
     * any delivery; used only by the source-side screens.
     */
    @Column(name = "local_identity", nullable = false)
    private String localIdentity;

    public Subject(String pseudonym, Site site, String region, String localIdentity) {
        this.pseudonym = pseudonym;
        this.site = site;
        this.region = region;
        this.localIdentity = localIdentity;
    }
}
