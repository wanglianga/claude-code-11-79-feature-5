package com.ctrial.model;

import com.ctrial.model.Enums.QuarantineKind;
import com.ctrial.model.Enums.QuarantineState;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Quarantine lane. Any race the platform cannot adjudicate deterministically
 * (e.g. a withdrawal and a visit correction with the same effective instant
 * and no approved tie-break) lands here instead of being guessed.
 */
@Entity
@Table(name = "quarantine_item")
@Getter
@Setter
@NoArgsConstructor
public class QuarantineItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QuarantineKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QuarantineState state;

    @Column(name = "stable_id")
    private String stableId;

    @Column(name = "subject_pseudonym")
    private String subjectPseudonym;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "event_id")
    private LocalEvent event;

    @Column(length = 2000)
    private String detail;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolution_note", length = 1000)
    private String resolutionNote;
}
