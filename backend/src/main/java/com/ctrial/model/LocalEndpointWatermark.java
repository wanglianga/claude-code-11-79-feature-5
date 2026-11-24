package com.ctrial.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Local-plane watermark for the endpoint assessment ledger. It is deliberately
 * separate from the cross-domain transfer {@link Watermark} table: endpoint
 * readouts are evaluated only inside the local domain, and advancing this
 * sequence never moves the transfer/analysis watermarks that pin the legacy
 * monthly report.
 */
@Entity
@Table(name = "local_endpoint_watermark", uniqueConstraints = {
        @UniqueConstraint(name = "uq_endpoint_wm_site", columnNames = {"site_code"})
})
@Getter
@Setter
@NoArgsConstructor
public class LocalEndpointWatermark {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_code", nullable = false, unique = true)
    private String siteCode;

    /** Highest durable endpoint-assessment source sequence confirmed at the site. */
    @Column(name = "confirmed_seq", nullable = false)
    private long confirmedSeq;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(length = 500)
    private String note;
}
