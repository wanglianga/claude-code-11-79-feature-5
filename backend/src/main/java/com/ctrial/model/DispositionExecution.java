package com.ctrial.model;

import com.ctrial.model.Enums.DispositionState;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Execution record proving that a disposition rule was applied to a concrete
 * derived artifact (delivery / subject-derived extract) after a withdrawal.
 * The underlying derived artifact may be restricted or destroyed, but the
 * original delivery summary row is never deleted — this ledger records what
 * happened without falsifying history.
 */
@Entity
@Table(name = "disposition_execution")
@Getter
@Setter
@NoArgsConstructor
public class DispositionExecution {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "rule_id")
    private DispositionRule rule;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "delivery_id")
    private Delivery delivery;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "subject_id")
    private Subject subject;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DispositionState state;

    @Column(name = "affected_rows", nullable = false)
    private int affectedRows;

    @Column(name = "scheduled_for")
    private Instant scheduledFor;

    @Column(name = "executed_at")
    private Instant executedAt;

    @Column(length = 1000)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
