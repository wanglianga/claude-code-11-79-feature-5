package com.ctrial.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** A participating site. Region drives residency; the local/global split uses it. */
@Entity
@Table(name = "site")
@Getter
@Setter
@NoArgsConstructor
public class Site {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String region;

    /** Source system label, e.g. "CN-HUB-1". Watermarks and transfer confirmations are per site. */
    @Column(nullable = false)
    private String sourceSystem;

    @Column(nullable = false)
    private boolean chinaResident;

    public Site(String code, String name, String region, String sourceSystem, boolean chinaResident) {
        this.code = code;
        this.name = name;
        this.region = region;
        this.sourceSystem = sourceSystem;
        this.chinaResident = chinaResident;
    }
}
