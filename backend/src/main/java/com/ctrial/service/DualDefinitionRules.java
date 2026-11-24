package com.ctrial.service;

import com.ctrial.model.AnalysisDefinition;
import com.ctrial.model.EndpointAssessment;
import com.ctrial.model.Enums.EgressBlockReason;
import com.ctrial.model.Enums.EndpointQualification;
import com.ctrial.model.Enums.ReclassificationKind;
import com.ctrial.model.ProtocolDeviationEntry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure dual-definition rule component, free of persistence. The same code runs
 * for every site on the same frozen records and produces two independent
 * encodings — legacy week-12 vs revised weeks-10–14 — plus the local-plane
 * egress verdict.
 *
 * <p>Design mirrors {@link EligibilityEngine}: callers supply only facts that
 * were effective at the relevant instants, and every outcome carries a full
 * reason trace. Nothing here writes or even reads a ledger, so the encoding is
 * deterministic and testable in isolation.
 */
public final class DualDefinitionRules {

    public static final String TARGET_WEEK = "12";

    private DualDefinitionRules() {
    }

    /** Everything known about one subject in the local plane at the comparison instant. */
    public record SubjectFacts(
            String pseudonym,
            String siteCode,
            String region,
            String riskStratum,
            boolean chinaResident,
            List<EndpointAssessment> assessments,
            List<ProtocolDeviationEntry> deviations,
            boolean crossBorderConsentActive,
            boolean crossBorderWithdrawalEffective,
            long siteConfirmedSeq,
            Instant legacyFreezeAt,
            Instant comparisonAnalysisAt,
            AnalysisDefinition legacy,
            AnalysisDefinition revised) {

        public boolean hasClassOneDeviation() {
            return deviations.stream().anyMatch(ProtocolDeviationEntry::isClassOne);
        }
    }

    public record Encoding(EndpointQualification qualification, List<String> reasons,
                           String selectedStableId, Integer selectedWeek, Integer distanceWeeks) {
        static Encoding of(EndpointQualification q, List<String> reasons, EndpointAssessment a, Integer distance) {
            return new Encoding(q, List.copyOf(reasons), a == null ? null : a.getStableId(),
                    a == null ? null : a.getStudyWeek(), distance);
        }
    }

    public record SubjectEncoding(
            Encoding legacy,
            Encoding revised,
            EgressBlockReason egressBlock,
            List<String> egressReasons,
            boolean notEvaluable,
            boolean windowSelectionDiffers,
            boolean dualObservationOpportunity,
            boolean commonCohortMember,
            ReclassificationKind reclassification) {
    }

    /** A test is durable only when its source sequence is at or below the site's confirmed watermark. */
    static boolean durable(EndpointAssessment a, long confirmedSeq) {
        return a.getSourceSeq() <= confirmedSeq;
    }

    static boolean adjudicatedAt(EndpointAssessment a, Instant at) {
        return a.getAdjudicatedAt() != null && !a.getAdjudicatedAt().isAfter(at);
    }

    // ------------------------------------------------------------------
    // Legacy definition: exactly one accepted week — week 12.
    // ------------------------------------------------------------------
    public static Encoding evaluateLegacy(SubjectFacts f) {
        List<EndpointAssessment> week12 = f.assessments().stream()
                .filter(a -> a.getStudyWeek() == f.legacy().getTargetWeek())
                .sorted(Comparator.comparing(EndpointAssessment::getOccurredAt)
                        .thenComparing(EndpointAssessment::getStableId))
                .toList();
        List<String> reasons = new ArrayList<>();
        if (week12.isEmpty()) {
            reasons.add("LEGACY_NO_WEEK12_TEST");
            return Encoding.of(EndpointQualification.NO_QUALIFYING_TEST, reasons, null, null);
        }
        EndpointAssessment chosen = week12.get(week12.size() - 1);
        if (!durable(chosen, f.siteConfirmedSeq())) {
            reasons.add("LEGACY_WEEK12_ABOVE_LOCAL_WATERMARK");
            return Encoding.of(EndpointQualification.NOT_EVALUABLE, reasons, chosen, null);
        }
        if (chosen.getAdjudicatedAt() == null) {
            reasons.add("LEGACY_ADJUDICATION_PENDING");
            return Encoding.of(EndpointQualification.NOT_EVALUABLE, reasons, chosen, null);
        }
        if (chosen.getAdjudicatedAt().isAfter(f.legacyFreezeAt())) {
            // The frozen monthly plane could not know this adjudication; the legacy side stays not evaluable.
            reasons.add("LEGACY_ADJUDICATION_AFTER_WATERMARK");
            reasons.add("FROZEN_PLANE_NOT_RECOMPUTED");
            return Encoding.of(EndpointQualification.NOT_EVALUABLE, reasons, chosen, null);
        }
        if (!chosen.isQualifyingResult()) {
            reasons.add("LEGACY_WEEK12_RESULT_NOT_QUALIFYING");
            return Encoding.of(EndpointQualification.NO_QUALIFYING_TEST, reasons, null, null);
        }
        reasons.add("LEGACY_WEEK12_EXACT_MATCH");
        return Encoding.of(EndpointQualification.QUALIFIED, reasons, chosen, 0);
    }

    // ------------------------------------------------------------------
    // Revised definition: closest qualifying test within weeks 10–14;
    // a class-1 deviation is an exclusion, not merely a flag.
    // ------------------------------------------------------------------
    public static Encoding evaluateRevised(SubjectFacts f) {
        List<String> reasons = new ArrayList<>();
        if (f.hasClassOneDeviation()) {
            reasons.add("REVISED_CLASS1_DEVIATION_EXCLUSION");
            return Encoding.of(EndpointQualification.EXCLUDED_DEVIATION, reasons, null, null);
        }
        int lo = f.revised().getWindowStartWeek();
        int hi = f.revised().getWindowEndWeek();
        List<EndpointAssessment> inWindow = f.assessments().stream()
                .filter(a -> a.getStudyWeek() >= lo && a.getStudyWeek() <= hi)
                .sorted(Comparator.comparingInt((EndpointAssessment a) -> Math.abs(a.getStudyWeek() - f.revised().getTargetWeek()))
                        .thenComparing(EndpointAssessment::getOccurredAt)
                        .thenComparing(EndpointAssessment::getStableId))
                .toList();
        if (inWindow.isEmpty()) {
            reasons.add("REVISED_NO_TEST_IN_WINDOW_10_14");
            return Encoding.of(EndpointQualification.NO_QUALIFYING_TEST, reasons, null, null);
        }
        boolean anyAboveWatermark = inWindow.stream().anyMatch(a -> !durable(a, f.siteConfirmedSeq()));
        List<EndpointAssessment> usable = inWindow.stream()
                .filter(a -> durable(a, f.siteConfirmedSeq()))
                .filter(a -> a.isQualifyingResult())
                .filter(a -> adjudicatedAt(a, f.comparisonAnalysisAt()))
                .toList();
        if (usable.isEmpty()) {
            if (anyAboveWatermark) {
                reasons.add("REVISED_WINDOW_CANDIDATE_ABOVE_LOCAL_WATERMARK");
                return Encoding.of(EndpointQualification.NOT_EVALUABLE, reasons, null, null);
            }
            reasons.add("REVISED_NO_QUALIFYING_TEST_IN_WINDOW");
            return Encoding.of(EndpointQualification.NO_QUALIFYING_TEST, reasons, null, null);
        }
        EndpointAssessment chosen = usable.get(0);
        int distance = Math.abs(chosen.getStudyWeek() - f.revised().getTargetWeek());
        reasons.add("REVISED_CLOSEST_IN_WINDOW_10_14");
        if (distance > 0) {
            reasons.add("REVISED_NON_TARGET_WEEK_SELECTED");
        }
        return Encoding.of(EndpointQualification.QUALIFIED, reasons, chosen, distance);
    }

    // ------------------------------------------------------------------
    // Egress: a China-resident row leaves the local plane only when a
    // cross-border grant is active and no withdrawal is effective. Aggregate
    // counts may still cross; subject rows may not.
    // ------------------------------------------------------------------
    public static EgressBlockReason evaluateEgress(SubjectFacts f) {
        boolean anyAbove = f.assessments().stream().anyMatch(a -> !durable(a, f.siteConfirmedSeq()));
        if (f.crossBorderWithdrawalEffective()) {
            return EgressBlockReason.CONSENT_WITHDRAWN;
        }
        if (!f.crossBorderConsentActive()) {
            return EgressBlockReason.NO_CROSS_BORDER_CONSENT;
        }
        if (anyAbove) {
            return EgressBlockReason.ABOVE_LOCAL_WATERMARK;
        }
        return EgressBlockReason.NONE;
    }

    public static SubjectEncoding encode(SubjectFacts f) {
        Encoding legacy = evaluateLegacy(f);
        Encoding revised = evaluateRevised(f);
        EgressBlockReason egress = evaluateEgress(f);

        List<String> egressReasons = new ArrayList<>();
        if (egress != EgressBlockReason.NONE) {
            egressReasons.add(egress.name());
            f.assessments().stream()
                    .filter(a -> !durable(a, f.siteConfirmedSeq()))
                    .findAny()
                    .ifPresent(a -> egressReasons.add(EgressBlockReason.ABOVE_LOCAL_WATERMARK.name() + ":" + a.getStableId()));
        }

        boolean notEvaluable = legacy.qualification() == EndpointQualification.NOT_EVALUABLE
                || revised.qualification() == EndpointQualification.NOT_EVALUABLE;
        ReclassificationKind kind = reclassify(legacy.qualification(), revised.qualification());

        boolean qualifyingWeek12 = f.assessments().stream()
                .anyMatch(a -> a.getStudyWeek() == 12 && a.isQualifyingResult()
                        && durable(a, f.siteConfirmedSeq()) && adjudicatedAt(a, f.comparisonAnalysisAt()));
        boolean qualifyingOtherInWindow = f.assessments().stream()
                .anyMatch(a -> a.getStudyWeek() >= 10 && a.getStudyWeek() <= 14 && a.getStudyWeek() != 12
                        && a.isQualifyingResult() && durable(a, f.siteConfirmedSeq())
                        && adjudicatedAt(a, f.comparisonAnalysisAt()));
        boolean dualOpportunity = qualifyingWeek12 && qualifyingOtherInWindow;

        boolean windowDiffers = revised.qualification() == EndpointQualification.QUALIFIED
                && revised.selectedWeek() != null && revised.selectedWeek() != 12;

        boolean commonCohort = dualOpportunity && !notEvaluable && !f.hasClassOneDeviation()
                && egress == EgressBlockReason.NONE;

        return new SubjectEncoding(legacy, revised, egress, List.copyOf(egressReasons), notEvaluable,
                windowDiffers, dualOpportunity, commonCohort, kind);
    }

    private static ReclassificationKind reclassify(EndpointQualification legacy, EndpointQualification revised) {
        if (legacy == EndpointQualification.NOT_EVALUABLE || revised == EndpointQualification.NOT_EVALUABLE) {
            return ReclassificationKind.INDETERMINATE;
        }
        boolean lq = legacy == EndpointQualification.QUALIFIED;
        boolean rq = revised == EndpointQualification.QUALIFIED;
        if (lq && rq) {
            return ReclassificationKind.RETAINED;
        }
        if (!lq && rq) {
            return ReclassificationKind.NEWLY_QUALIFIED;
        }
        if (lq) {
            return ReclassificationKind.LOST;
        }
        return ReclassificationKind.NEVER_QUALIFIED;
    }
}
