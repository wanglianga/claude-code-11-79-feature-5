package com.ctrial;

import com.ctrial.config.ComparisonScenarioSeeder;
import com.ctrial.config.DemoSeeder;
import com.ctrial.model.*;
import com.ctrial.model.Enums.*;
import com.ctrial.repo.*;
import com.ctrial.service.DefinitionComparisonService;
import com.ctrial.service.DualDefinitionRules;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Acceptance tests for the definition-impact comparison subdomain. The same
 * frozen records are encoded locally under both registered definitions, and
 * every published comparison is a new, separately-watermarked version that
 * never rewrites the frozen monthly report.
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class DefinitionComparisonTest {

    private static final String FMR = "FMR-2026-M05";
    private static final int DAY = ComparisonScenarioSeeder.COMPARISON_DAY;

    @Autowired DefinitionComparisonService service;
    @Autowired FrozenMonthlyReportRepo frozenRepo;
    @Autowired AnalysisDefinitionRepo definitionRepo;
    @Autowired ComparisonRunRepo runRepo;
    @Autowired DualDefinitionRowRepo rowRepo;
    @Autowired ComparisonVersionRepo versionRepo;
    @Autowired LocalBridgeAggregateRepo bridgeRepo;
    @Autowired DenominatorContributionRepo contributionRepo;
    @Autowired ComparisonWatermarkRepo watermarkRepo;

    private DualDefinitionRow row(String runCode, String pseudonym) {
        ComparisonRun run = runRepo.findByCode(runCode).orElseThrow();
        return rowRepo.findByComparisonRunIdAndSubjectPseudonym(run.getId(), pseudonym).orElseThrow();
    }

    private DefinitionComparisonService.EvaluationSummary eval(String site, BridgeRoute route) {
        return service.evaluateSite(FMR, site, route, DAY, "test");
    }

    // ---------------- registry ----------------

    @Test
    void registryPinsExactlyTwoLockedDefinitionsWithIntervals() {
        List<AnalysisDefinition> defs = service.definitions();
        assertThat(defs).hasSize(2);
        AnalysisDefinition legacy = definitionRepo.findByDefinitionKey(DefinitionKey.LEGACY_W12).orElseThrow();
        AnalysisDefinition revised = definitionRepo.findByDefinitionKey(DefinitionKey.REVISED_W10_14).orElseThrow();
        assertThat(legacy.isLocked()).isTrue();
        assertThat(revised.isLocked()).isTrue();
        assertThat(legacy.getWindowStartWeek()).isEqualTo(12);
        assertThat(legacy.getWindowEndWeek()).isEqualTo(12);
        assertThat(legacy.isClosestToTarget()).isFalse();
        assertThat(legacy.isDeviationClass1Exclusion()).isFalse();
        assertThat(revised.getWindowStartWeek()).isEqualTo(10);
        assertThat(revised.getWindowEndWeek()).isEqualTo(14);
        assertThat(revised.isClosestToTarget()).isTrue();
        assertThat(revised.isDeviationClass1Exclusion()).isTrue();
        assertThat(revised.getApplicableFrom()).isAfter(legacy.getApplicableFrom());
    }

    // ---------------- dual-definition encoding ----------------

    @Test
    void subjectC_week12AndWeek11BothQualified_dualOpportunityAndLegacyStaysExact() {
        var summary = eval("CN-01", BridgeRoute.DUAL_BRIDGE);
        DualDefinitionRow c = row(summary.runCode(), "PSN-C3");
        assertThat(c.getLegacyQualification()).isEqualTo(EndpointQualification.QUALIFIED);
        assertThat(c.getLegacySelectedWeek()).isEqualTo(12);
        assertThat(c.getLegacyReasons()).contains("LEGACY_WEEK12_EXACT_MATCH");
        assertThat(c.getRevisedQualification()).isEqualTo(EndpointQualification.QUALIFIED);
        // closest-to-target still picks week 12 even though the week-11 test also qualifies
        assertThat(c.getRevisedSelectedWeek()).isEqualTo(12);
        assertThat(c.getRevisedDistanceWeeks()).isZero();
        assertThat(c.isDualObservationOpportunity()).isTrue();
        assertThat(c.isCommonCohortMember()).isTrue();
        assertThat(c.getReclassification()).isEqualTo(ReclassificationKind.RETAINED);
        assertThat(c.getEgressBlock()).isEqualTo(EgressBlockReason.NONE);
    }

    @Test
    void subjectE_week11Only_isNewlyQualifiedWithWindowSelectionDifference() {
        var summary = eval("CN-01", BridgeRoute.DUAL_BRIDGE);
        DualDefinitionRow e = row(summary.runCode(), "PSN-E5");
        assertThat(e.getLegacyQualification()).isEqualTo(EndpointQualification.NO_QUALIFYING_TEST);
        assertThat(e.getLegacyReasons()).contains("LEGACY_NO_WEEK12_TEST");
        assertThat(e.getRevisedQualification()).isEqualTo(EndpointQualification.QUALIFIED);
        assertThat(e.getRevisedSelectedWeek()).isEqualTo(11);
        assertThat(e.isWindowSelectionDiffers()).isTrue();
        assertThat(e.getReclassification()).isEqualTo(ReclassificationKind.NEWLY_QUALIFIED);
        assertThat(e.isDualObservationOpportunity()).isFalse();
    }

    @Test
    void subjectF_classOneDeviation_qualifiedUnderLegacyButExcludedUnderRevised() {
        var summary = eval("CN-01", BridgeRoute.DUAL_BRIDGE);
        DualDefinitionRow f = row(summary.runCode(), "PSN-F6");
        assertThat(f.getLegacyQualification()).isEqualTo(EndpointQualification.QUALIFIED);
        assertThat(f.getRevisedQualification()).isEqualTo(EndpointQualification.EXCLUDED_DEVIATION);
        assertThat(f.getRevisedReasons()).contains("REVISED_CLASS1_DEVIATION_EXCLUSION");
        assertThat(f.getReclassification()).isEqualTo(ReclassificationKind.LOST);
    }

    @Test
    void subjectD_adjudicationAfterLegacyWatermark_notEvaluableOnLegacySideButQualifiedUnderRevised() {
        var summary = eval("CN-02", BridgeRoute.DUAL_BRIDGE);
        DualDefinitionRow d = row(summary.runCode(), "PSN-D4");
        assertThat(d.getLegacyQualification()).isEqualTo(EndpointQualification.NOT_EVALUABLE);
        assertThat(d.getLegacyReasons()).contains("LEGACY_ADJUDICATION_AFTER_WATERMARK");
        assertThat(d.getRevisedQualification()).isEqualTo(EndpointQualification.QUALIFIED);
        assertThat(d.isNotEvaluable()).isTrue();
        assertThat(d.getReclassification()).isEqualTo(ReclassificationKind.INDETERMINATE);
    }

    @Test
    void subjectB_crossBorderConsentWithdrawn_locallyEncodedButEgressBlocked() {
        var summary = eval("SG-01", BridgeRoute.DUAL_BRIDGE);
        DualDefinitionRow b = row(summary.runCode(), "PSN-B2");
        // local evaluation still produces both encodings — withdrawal governs egress, not qualification
        assertThat(b.getLegacyQualification()).isEqualTo(EndpointQualification.QUALIFIED);
        assertThat(b.getRevisedQualification()).isEqualTo(EndpointQualification.QUALIFIED);
        assertThat(b.getEgressBlock()).isEqualTo(EgressBlockReason.CONSENT_WITHDRAWN);
        assertThat(b.isCommonCohortMember()).isFalse();
        assertThat(b.getReclassification()).isEqualTo(ReclassificationKind.RETAINED);
    }

    @Test
    void subjectA_chinaOnlyConsent_qualifiesLocallyButSubjectRowsCannotEgress() {
        var summary = eval("CN-01", BridgeRoute.DUAL_BRIDGE);
        DualDefinitionRow a = row(summary.runCode(), "PSN-A1");
        assertThat(a.getLegacyQualification()).isEqualTo(EndpointQualification.QUALIFIED);
        assertThat(a.getRevisedQualification()).isEqualTo(EndpointQualification.QUALIFIED);
        assertThat(a.getEgressBlock()).isEqualTo(EgressBlockReason.NO_CROSS_BORDER_CONSENT);
        assertThat(a.isChinaResident()).isTrue();
    }

    // ---------------- independent watermarks ----------------

    @Test
    void eachEvaluationGetsItsOwnWatermarkAndNeverTouchesTheFrozenPin() {
        FrozenMonthlyReport before = frozenRepo.findByCode(FMR).orElseThrow();
        long wmBefore = watermarkRepo.count();
        var cn01 = eval("CN-01", BridgeRoute.DUAL_BRIDGE);
        var cn02 = eval("CN-02", BridgeRoute.DUAL_BRIDGE);
        assertThat(watermarkRepo.count()).isEqualTo(wmBefore + 2);
        ComparisonRun r1 = runRepo.findByCode(cn01.runCode()).orElseThrow();
        ComparisonRun r2 = runRepo.findByCode(cn02.runCode()).orElseThrow();
        assertThat(r1.getWatermarkCode()).isNotEqualTo(r2.getWatermarkCode());
        FrozenMonthlyReport after = frozenRepo.findByCode(FMR).orElseThrow();
        assertThat(after.getPinnedWatermark()).isEqualTo(before.getPinnedWatermark());
        assertThat(after.getReportDigest()).isEqualTo(before.getReportDigest());
    }

    // ---------------- publishing: bridge, intervals, coverage, gate ----------------

    @Test
    void dualBridgePublish_emitsAggregateOnlyIntervalsAndBlocksMergedConclusion() {
        FrozenMonthlyReport frozen = frozenRepo.findByCode(FMR).orElseThrow();
        String digestBefore = frozen.getReportDigest();
        int qualifiedBefore = frozen.getLegacyQualifiedTotal();

        var cn01 = eval("CN-01", BridgeRoute.DUAL_BRIDGE);
        var cn02 = eval("CN-02", BridgeRoute.DUAL_BRIDGE);
        var sg = eval("SG-01", BridgeRoute.DUAL_BRIDGE);
        ComparisonVersion version = service.publish(List.of(cn01.runCode(), cn02.runCode(), sg.runCode()), "test");

        assertThat(version.getRoute()).isEqualTo(BridgeRoute.DUAL_BRIDGE);
        assertThat(version.getLinkedFrozenReportCode()).isEqualTo(FMR);
        assertThat(version.getStatus()).isEqualTo(ComparisonStatus.PUBLISHED_BLOCKED);
        assertThat(version.isMergeBlocked()).isTrue();
        assertThat(version.getBlockReasons()).contains("NOT_EVALUABLE_SUBJECTS:1");

        // legacy figures are echoed from the frozen report, never recomputed
        assertThat(version.getLegacyQualified()).isEqualTo(8);
        assertThat(version.getLegacyDenominator()).isEqualTo(12);

        // revised qualified point = C, A (retained) + E (newly) + B (retained) = 4;
        // unresolved rows (A consent, B withdrawal, D adjudication) widen the band to 7
        assertThat(version.getRevisedQualifiedPoint()).isEqualTo(4);
        assertThat(version.getRevisedQualifiedLower()).isEqualTo(4);
        assertThat(version.getRevisedQualifiedUpper()).isEqualTo(7);
        assertThat(version.getWindowSelectionDiffTotal()).isEqualTo(1);
        assertThat(version.getNotEvaluableTotal()).isEqualTo(1);
        assertThat(version.getEgressBlockedTotal()).isEqualTo(2);

        // the CN-01 efficacy bridge carries the full reclassification matrix as counts only
        LocalBridgeAggregate cn01Efficacy = bridgeRepo
                .findByComparisonVersionIdOrderBySiteCodeAscEndpointKindAsc(version.getId()).stream()
                .filter(a -> a.getSiteCode().equals("CN-01") && a.getEndpointKind().equals("EFFICACY"))
                .findFirst().orElseThrow();
        assertThat(cn01Efficacy.getRetainedCount()).isEqualTo(2);
        assertThat(cn01Efficacy.getNewlyQualifiedCount()).isEqualTo(1);
        assertThat(cn01Efficacy.getLostCount()).isEqualTo(1);
        assertThat(cn01Efficacy.getWindowSelectionDiffCount()).isEqualTo(1);
        assertThat(cn01Efficacy.getEgressBlockedCount()).isEqualTo(1);
        assertThat(cn01Efficacy.getUncertaintyLower()).isEqualTo(3);
        assertThat(cn01Efficacy.getUncertaintyUpper()).isEqualTo(4);

        // the HIGH-risk overlap shortfall is recorded on the denominator contribution ledger
        DenominatorContribution lowCrossBorder = contributionRepo
                .findByComparisonVersionIdOrderBySiteCodeAscConsentScopeAscRiskStratumAsc(version.getId()).stream()
                .filter(c -> c.getSiteCode().equals("CN-01") && c.getConsentScope().equals("CROSS_BORDER")
                        && c.getRiskStratum().equals("LOW"))
                .findFirst().orElseThrow();
        assertThat(lowCrossBorder.getOverlapFraction()).isEqualTo(0.5);
        assertThat(lowCrossBorder.isOverlapInsufficient()).isTrue();
        assertThat(version.getInsufficientOverlapCells()).isGreaterThan(0);

        // no subject-level content exists on the published version
        assertThat(version.getComparisonSummary()).doesNotContain("PSN-");

        // the frozen monthly report is byte-for-byte unchanged
        FrozenMonthlyReport untouched = frozenRepo.findByCode(FMR).orElseThrow();
        assertThat(untouched.getReportDigest()).isEqualTo(digestBefore);
        assertThat(untouched.getLegacyQualifiedTotal()).isEqualTo(qualifiedBefore);
        assertThat(untouched.isImmutable()).isTrue();
    }

    @Test
    void cohortRoutePublishesASeparateVersionAndAppliesTheWeightStabilityConstraint() {
        var cn01 = eval("CN-01", BridgeRoute.COHORT_BRIDGE);
        var cn02 = eval("CN-02", BridgeRoute.COHORT_BRIDGE);
        var sg = eval("SG-01", BridgeRoute.COHORT_BRIDGE);
        ComparisonVersion cohort = service.publish(List.of(cn01.runCode(), cn02.runCode(), sg.runCode()), "test");

        assertThat(cohort.getCode()).startsWith("CMP-COHORT-");
        assertThat(cohort.getRoute()).isEqualTo(BridgeRoute.COHORT_BRIDGE);
        // only C satisfies the pre-declared common-observation-opportunity requirement (5 of 6 excluded)
        assertThat(cohort.getCohortExcludedCount()).isEqualTo(5);
        assertThat(cohort.isMergeBlocked()).isTrue();
        assertThat(cohort.getBlockReasons()).contains("COHORT_WEIGHT_UNSTABLE");
        assertThat(cohort.getStatus()).isEqualTo(ComparisonStatus.PUBLISHED_BLOCKED);

        // standardisation weights follow the frozen risk-stratum mix
        assertThat(contributionRepo.findByComparisonVersionIdOrderBySiteCodeAscConsentScopeAscRiskStratumAsc(
                cohort.getId()).stream().mapToDouble(DenominatorContribution::getStandardisationWeight).sum())
                .isGreaterThan(0.0);
    }

    @Test
    void theTwoRoutesProduceTwoDistinctVersionsAndNeitherReplacesTheFrozenReport() {
        var dual = eval("CN-01", BridgeRoute.DUAL_BRIDGE);
        var cohort = eval("CN-01", BridgeRoute.COHORT_BRIDGE);
        ComparisonVersion v1 = service.publish(List.of(dual.runCode()), "test");
        ComparisonVersion v2 = service.publish(List.of(cohort.runCode()), "test");
        assertThat(v1.getCode()).startsWith("CMP-DUAL-");
        assertThat(v2.getCode()).startsWith("CMP-COHORT-");
        assertThat(v1.getCode()).isNotEqualTo(v2.getCode());
        assertThat(v1.getVersionDigest()).isNotEqualTo(v2.getVersionDigest());
        assertThat(versionRepo.findByLinkedFrozenReportCodeOrderByPublishedAtDesc(FMR)).hasSizeGreaterThanOrEqualTo(2);
        // the baseline remains exactly one immutable row
        assertThat(frozenRepo.findAll()).hasSize(1);
        assertThat(frozenRepo.findByCode(FMR).orElseThrow().isImmutable()).isTrue();
    }

    @Test
    void mixedRoutesCannotBePublishedIntoOneVersion() {
        var dual = eval("CN-01", BridgeRoute.DUAL_BRIDGE);
        var cohort = eval("CN-02", BridgeRoute.COHORT_BRIDGE);
        assertThatThrownBy(() -> service.publish(List.of(dual.runCode(), cohort.runCode()), "test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mix routes");
    }

    @Test
    void annotationsAreAppendOnlyAndDoNotModifyTheVersion() {
        var cn01 = eval("CN-01", BridgeRoute.DUAL_BRIDGE);
        ComparisonVersion v = service.publish(List.of(cn01.runCode()), "test");
        String digestBefore = v.getVersionDigest();
        service.annotate(new DefinitionComparisonService.AnnotationRequest(v.getId(), "CN-01", "EFFICACY",
                "WINDOW_SELECTION", "Week-11 test selected only where no week-12 test existed (subject E).",
                "global-statistics-team"), "test");
        service.annotate(new DefinitionComparisonService.AnnotationRequest(v.getId(), "CN-01", "EFFICACY",
                "OVERLAP", "LOW stratum overlap 0.50 is below the 0.60 stability floor.", "global-statistics-team"),
                "test");
        assertThat(service.annotations(v.getId())).hasSize(2);
        assertThat(versionRepo.findByCode(v.getCode()).orElseThrow().getVersionDigest()).isEqualTo(digestBefore);
    }

    @Test
    void pureRulesRejectAnUnadjudicatedWindowCandidateAsNotEvaluable() {
        AnalysisDefinition revised = definitionRepo.findByDefinitionKey(DefinitionKey.REVISED_W10_14).orElseThrow();
        AnalysisDefinition legacy = definitionRepo.findByDefinitionKey(DefinitionKey.LEGACY_W12).orElseThrow();
        EndpointAssessment a = new EndpointAssessment();
        a.setStableId("EP-X-W12");
        a.setStudyWeek(12);
        a.setSourceSeq(1);
        a.setQualifyingResult(true);
        a.setAdjudicatedAt(null);
        var facts = new DualDefinitionRules.SubjectFacts("PSN-X", "CN-01", "CN", "LOW", true,
                List.of(a), List.of(), true, false, 5L,
                DemoSeeder.day(90), DemoSeeder.day(100), legacy, revised);
        var encoding = DualDefinitionRules.encode(facts);
        assertThat(encoding.legacy().qualification()).isEqualTo(EndpointQualification.NOT_EVALUABLE);
        assertThat(encoding.revised().qualification()).isEqualTo(EndpointQualification.NO_QUALIFYING_TEST);
        assertThat(encoding.reclassification()).isEqualTo(ReclassificationKind.INDETERMINATE);
    }
}
