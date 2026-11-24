package com.ctrial.model;

import com.ctrial.common.Text;
import com.ctrial.model.Enums.Purpose;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Set;

/**
 * A sponsor/ethics-approved data-use envelope. The global team may only use
 * records inside the <b>intersection</b> of all these dimensions: purpose,
 * fields, sites, observation window and protocol versions. An approval never
 * expands what consent granted; it only narrows it.
 */
@Entity
@Table(name = "data_use_approval")
@Getter
@Setter
@NoArgsConstructor
public class DataUseApproval {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Purpose purpose;

    /** GLOBAL approvals may read from any approved destination; local approvals are CN-only. */
    @Column(name = "cross_border", nullable = false)
    private boolean crossBorder;

    @Column(name = "destination_regions", nullable = false, length = 300)
    private String destinationRegions;

    @Column(name = "site_scope", nullable = false, length = 400)
    private String siteScope;

    @Column(name = "field_groups", nullable = false, length = 400)
    private String fieldGroups;

    @Column(name = "protocol_versions", nullable = false, length = 400)
    private String protocolVersions;

    @Column(name = "observation_from", nullable = false)
    private Instant observationFrom;

    @Column(name = "observation_to", nullable = false)
    private Instant observationTo;

    @Column(name = "approved_at", nullable = false)
    private Instant approvedAt;

    @Column(name = "approved_by", nullable = false)
    private String approvedBy;

    @Column(nullable = false)
    private boolean active = true;

    @Column(length = 1000)
    private String note;

    public Set<String> siteSet() {
        return Text.stringSet(siteScope);
    }

    public Set<String> fieldGroupSet() {
        return Text.stringSet(fieldGroups);
    }

    public Set<String> protocolVersionSet() {
        return Text.stringSet(protocolVersions);
    }

    public Set<String> destinationRegionSet() {
        return Text.stringSet(destinationRegions);
    }

    public boolean coversSite(String siteCode) {
        Set<String> sites = siteSet();
        return sites.isEmpty() || sites.contains(siteCode.toUpperCase());
    }

    public boolean coversProtocol(String version) {
        Set<String> versions = protocolVersionSet();
        return versions.isEmpty() || versions.contains(version.toUpperCase());
    }

    public boolean windowContains(Instant instant) {
        return !instant.isBefore(observationFrom) && instant.isBefore(observationTo);
    }
}
