package com.ctrial.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Root of the study aggregate; also carries the study-day clock used by every rule. */
@Entity
@Table(name = "study")
@Getter
@Setter
@NoArgsConstructor
public class Study {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    /** Study day 1, 09:00 UTC. All dayN() conversions are relative to this instant. */
    @Column(nullable = false)
    private Instant epoch;

    @Column(nullable = false)
    private String sponsor;

    public Study(String code, String name, Instant epoch, String sponsor) {
        this.code = code;
        this.name = name;
        this.epoch = epoch;
        this.sponsor = sponsor;
    }
}
