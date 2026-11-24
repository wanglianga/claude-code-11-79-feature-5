package com.ctrial.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A pre-registered tie-break rule for cases where a withdrawal and a record
 * correction collide. Without a matching active rule the platform must isolate
 * the case in quarantine; it may never pick a side on its own.
 */
@Entity
@Table(name = "tiebreak_rule")
@Getter
@Setter
@NoArgsConstructor
public class TieBreakRule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    /** CORRECTION_WINS | WITHDRAWAL_WINS */
    @Column(nullable = false, length = 40)
    private String precedence;

    @Column(nullable = false)
    private String rationale;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt;

    @Column(name = "registered_by", nullable = false)
    private String registeredBy;

    @Column(nullable = false)
    private boolean active = true;
}
