package com.ctrial.model;

public final class Enums {

    private Enums() {
    }

    /**
     * Approved processing purpose. ALL is only valid inside a consent grant,
     * never as the purpose of a query or a delivery.
     */
    public enum Purpose {
        GLOBAL_ANALYSIS,
        CHINA_LOCAL_ANALYSIS,
        LOCAL_ANALYSIS,
        SAFETY_MONITORING,
        REGULATORY_SUBMISSION,
        ALL;

        public boolean isGlobal() {
            return this == GLOBAL_ANALYSIS;
        }

        public boolean isLocal() {
            return this == CHINA_LOCAL_ANALYSIS || this == LOCAL_ANALYSIS;
        }
    }

    /** Destination / source region codes. GLOBAL = any approved region. */
    public enum Region {
        GLOBAL, EU, CN, SG, DE, TH;

        public boolean covers(String region) {
            return this == GLOBAL || name().equalsIgnoreCase(region);
        }
    }

    /** Data domains flowing through the source ledger. Each has its own watermark. */
    public enum Domain {
        VISIT, LAB, AE, IMAGING, RANDOMIZATION, DEVIATION
    }

    public enum ConsentKind {
        GRANT, WITHDRAWAL
    }

    public enum ProtocolStatus {
        DRAFT,
        PARTIALLY_APPROVED,
        APPROVED,
        WITHDRAWN
    }

    /** Per-record availability. The three outcomes required by the spec. */
    public enum Eligibility {
        LOCAL_AVAILABLE,
        GLOBAL_AVAILABLE,
        PENDING,
        /** No lawful basis in any plane; reported separately, never silently counted as zero. */
        BLOCKED
    }

    /** Finer-grained adjudication used alongside the three availability outcomes. */
    public enum Verdict {
        ELIGIBLE,
        UNDETERMINED,
        NOT_ELIGIBLE
    }

    public enum SnapshotStatus {
        VALID,
        STALE,
        AUDIT_ONLY
    }

    public enum DeliveryStatus {
        ACTIVE,
        ACCESS_REVOKED,
        DESTROYED
    }

    public enum PlaneType {
        GLOBAL,
        LOCAL
    }

    public enum RuleAction {
        RETAIN_RESTRICTED,
        DESTROY_AFTER
    }

    public enum DispositionScope {
        STUDY,
        SITE
    }

    public enum DispositionState {
        APPLIED,
        SCHEDULED,
        EXECUTED
    }

    public enum QuarantineKind {
        RACE_WITHDRAWAL_CORRECTION,
        DUPLICATE_HASH_CONFLICT,
        CROSS_DOMAIN_MISMATCH,
        WATERMARK_INCONSISTENCY
    }

    public enum QuarantineState {
        OPEN,
        RESOLVED
    }

    public enum RecoveryStatus {
        PASS,
        FINDINGS
    }

    public enum Role {
        ADMIN,
        GLOBAL_ANALYST,
        CN_ANALYST,
        AUDITOR,
        SPONSOR
    }

    /**
     * The two fixed efficacy/safety endpoint definitions. The registry pins
     * exactly these two rules together with their applicability intervals; a
     * comparison version may never introduce a third definition in place of
     * either one.
     */
    public enum DefinitionKey {
        /** Legacy frozen definition: only the week-12 post-randomisation test counts. */
        LEGACY_W12,
        /** Revised definition: closest qualifying test within weeks 10–14; class-1 deviation excludes. */
        REVISED_W10_14
    }

    /** Dual-definition endpoint qualification, encoded separately for each definition on the same record. */
    public enum EndpointQualification {
        /** A qualifying endpoint test exists for this subject under this definition. */
        QUALIFIED,
        /** No qualifying test exists for this definition's window on the frozen record. */
        NO_QUALIFYING_TEST,
        /** A candidate exists but is outside the watermark / not yet adjudicated; cannot be evaluated. */
        NOT_EVALUABLE,
        /** Class-1 protocol deviation makes the subject an exclusion under this definition. */
        EXCLUDED_DEVIATION
    }

    /** Why a local result cannot leave the region; the bridge may carry only counts, never this row. */
    public enum EgressBlockReason {
        NONE,
        /** Cross-border consent grant is missing or purpose-scoped to local analysis only. */
        NO_CROSS_BORDER_CONSENT,
        /** A cross-border consent withdrawal effective at the comparison instant blocks egress. */
        CONSENT_WITHDRAWN,
        /** Record only exists above the local watermark; completeness cannot be proven for export. */
        ABOVE_LOCAL_WATERMARK,
        /** The endpoint adjudication arrived after the pinned (legacy) watermark. */
        ADJUDICATION_AFTER_WATERMARK
    }

    /** How a subject moves between the two definitions' endpoint sets. */
    public enum ReclassificationKind {
        /** Qualified under both definitions (with the same or a different test). */
        RETAINED,
        /** Not qualified under legacy, qualified under the revised window. */
        NEWLY_QUALIFIED,
        /** Qualified under legacy but excluded / no longer qualified under revised. */
        LOST,
        /** Not qualified under either definition. */
        NEVER_QUALIFIED,
        /** One or both sides are not evaluable; membership cannot be settled. */
        INDETERMINATE
    }

    /** The two pre-declared bridging routes; each produces its own separate comparison version. */
    public enum BridgeRoute {
        /** Dual-definition aggregation on subjects having both a W12 and an in-window qualifying test. */
        DUAL_BRIDGE,
        /** Pre-declared common-observation-opportunity cohort, standardised to the old risk-stratum mix. */
        COHORT_BRIDGE
    }

    /** Lifecycle of a comparison version. A blocked version must never be merged into an efficacy conclusion. */
    public enum ComparisonStatus {
        DRAFT,
        PUBLISHED,
        /** Published but the uncertainty gate blocks merging into a combined efficacy/safety conclusion. */
        PUBLISHED_BLOCKED,
        SUPERSEDED
    }
}
