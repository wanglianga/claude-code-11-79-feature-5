package com.ctrial.config;

import com.ctrial.common.Text;
import com.ctrial.model.*;
import com.ctrial.model.Enums.*;
import com.ctrial.repo.*;
import com.ctrial.service.AuditService;
import com.ctrial.service.EligibilityService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Seeds the definition-impact comparison scenario on top of the base A/B/C/D
 * ledger. It deliberately writes a frozen monthly report <b>first</b>, so every
 * later comparison runs against an immutable baseline it can link to but never
 * rewrite.
 *
 * <pre>
 *  D01..  base A/B/C/D scenario (see DemoSeeder)
 *  D60    F's class-1 protocol deviation (flag only under legacy; exclusion under revised)
 *  D78    week-11 endpoint tests (C, E efficacy; B safety)
 *  D85    week-12 endpoint tests (A, B, C, D, F)
 *  D88-89 adjudications land before the legacy freeze
 *  D90    legacy monthly plane freezes (analysis instant 23:00)
 *  D91    FMR-2026-M05 submitted; revised definition becomes applicable
 *  D92    A's week-13 test adjudicated (dual observation opportunity)
 *  D95    D's week-12 adjudication arrives — after the legacy watermark
 *  D100   comparison analysis instant: both definitions evaluated locally
 * </pre>
 */
@Component
@Order(20)
public class ComparisonScenarioSeeder implements ApplicationRunner {

    public static final int FREEZE_DAY = 90;
    public static final int SUBMIT_DAY = 91;
    public static final int COMPARISON_DAY = 100;

    private final AnalysisDefinitionRepo definitionRepo;
    private final FrozenMonthlyReportRepo frozenRepo;
    private final LocalEndpointWatermarkRepo endpointWatermarkRepo;
    private final EndpointAssessmentRepo assessmentRepo;
    private final ProtocolDeviationEntryRepo deviationRepo;
    private final SubjectRepo subjectRepo;
    private final SiteRepo siteRepo;
    private final ConsentRepo consentRepo;
    private final WatermarkRepo watermarkRepo;
    private final EligibilityService eligibility;
    private final AuditService audit;
    private final boolean seed;

    public ComparisonScenarioSeeder(AnalysisDefinitionRepo definitionRepo, FrozenMonthlyReportRepo frozenRepo,
                                    LocalEndpointWatermarkRepo endpointWatermarkRepo,
                                    EndpointAssessmentRepo assessmentRepo,
                                    ProtocolDeviationEntryRepo deviationRepo, SubjectRepo subjectRepo,
                                    SiteRepo siteRepo, ConsentRepo consentRepo, WatermarkRepo watermarkRepo,
                                    EligibilityService eligibility, AuditService audit,
                                    @org.springframework.beans.factory.annotation.Value("${app.demo.seed-on-startup:true}")
                                    boolean seed) {
        this.definitionRepo = definitionRepo;
        this.frozenRepo = frozenRepo;
        this.endpointWatermarkRepo = endpointWatermarkRepo;
        this.assessmentRepo = assessmentRepo;
        this.deviationRepo = deviationRepo;
        this.subjectRepo = subjectRepo;
        this.siteRepo = siteRepo;
        this.consentRepo = consentRepo;
        this.watermarkRepo = watermarkRepo;
        this.eligibility = eligibility;
        this.audit = audit;
        this.seed = seed;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!seed || frozenRepo.count() > 0) {
            return;
        }
        Study study = eligibility.study();
        Site cn1 = siteRepo.findByCode("CN-01").orElseThrow();
        Site cn2 = siteRepo.findByCode("CN-02").orElseThrow();
        Site sg = siteRepo.findByCode("SG-01").orElseThrow();

        // ---------------- 1. fixed definition registry (locked) ----------------
        Instant freezeAt = DemoSeeder.day(FREEZE_DAY).plus(23, java.time.temporal.ChronoUnit.HOURS);
        AnalysisDefinition legacy = definition("DEF-LEGACY-W12", DefinitionKey.LEGACY_W12,
                "Legacy endpoint definition — post-randomisation week 12 only", "legacy-1.0",
                12, 12, 12, false, false, DemoSeeder.day(1), DemoSeeder.day(SUBMIT_DAY),
                "Only the week-12 test is accepted; a class-1 deviation is documented as a flag, not an exclusion.");
        AnalysisDefinition revised = definition("DEF-REVISED-W10-14", DefinitionKey.REVISED_W10_14,
                "Revised endpoint definition — closest qualifying test within weeks 10–14", "revised-2.0",
                12, 10, 14, true, true, DemoSeeder.day(SUBMIT_DAY), DemoSeeder.day(365),
                "Window widened to weeks 10–14 with closest-to-target selection; class-1 deviations exclude.");

        // ---------------- 2. extra subjects with global grants ----------------
        Subject e = subjectRepo.save(new Subject("PSN-E5", cn1, "CN", "CHN-001-0011"));
        Subject f = subjectRepo.save(new Subject("PSN-F6", cn1, "CN", "CHN-001-0012"));
        grant(e, hour(2, 15));
        grant(f, hour(2, 16));

        // ---------------- 3. local endpoint watermarks (separate counter plane) ----------------
        endpointWatermark(cn1.getCode(), 30);
        endpointWatermark(cn2.getCode(), 20);
        endpointWatermark(sg.getCode(), 10);

        // ---------------- 4. endpoint assessments ----------------
        // C: week-11 AND week-12 both qualifying (revised still picks W12 as closest; dual opportunity)
        assessment("EP-C3-W11-E", "PSN-C3", cn1.getCode(), "EFFICACY", 11, hour(78, 10), hour(78, 12),
                hour(88, 9), 21, true, "LOW");
        assessment("EP-C3-W12-E", "PSN-C3", cn1.getCode(), "EFFICACY", 12, hour(85, 10), hour(85, 12),
                hour(88, 10), 22, true, "LOW");
        assessment("EP-C3-W12-S", "PSN-C3", cn1.getCode(), "SAFETY", 12, hour(85, 12), hour(85, 13),
                hour(88, 11), 23, true, "LOW");

        // A: China-only consent; W12 + W13 qualified locally, never exportable as subject rows
        assessment("EP-A1-W12-E", "PSN-A1", cn1.getCode(), "EFFICACY", 12, hour(85, 9), hour(85, 11),
                hour(89, 10), 24, true, "MEDIUM");
        assessment("EP-A1-W13-E", "PSN-A1", cn1.getCode(), "EFFICACY", 13, hour(92, 9), hour(92, 11),
                hour(93, 10), 25, true, "MEDIUM");

        // E: no week-12 test at all; a qualifying week-11 test — newly qualified only under the revised window
        assessment("EP-E5-W11-E", "PSN-E5", cn1.getCode(), "EFFICACY", 11, hour(78, 15), hour(78, 17),
                hour(88, 15), 26, true, "MEDIUM");

        // F: qualifying week-12 test, but a class-1 deviation — retained under legacy, excluded under revised
        assessment("EP-F6-W12-E", "PSN-F6", cn1.getCode(), "EFFICACY", 12, hour(85, 16), hour(85, 18),
                hour(89, 12), 27, true, "LOW");
        deviation("DEV-F6-C1", "PSN-F6", cn1.getCode(), 1, hour(60, 10), hour(60, 14),
                "Class-1 informed-consent process deviation; flag under legacy, exclusion under revised.");
        deviation("DEV-C3-C2", "PSN-C3", cn1.getCode(), 2, hour(70, 10), hour(70, 12),
                "Class-2 visit-window documentation deviation; never excluding.");

        // B: global consent withdrawn on day 12 — local qualifications hold, cross-border egress is blocked
        assessment("EP-B2-W12-E", "PSN-B2", sg.getCode(), "EFFICACY", 12, hour(85, 11), hour(85, 13),
                hour(87, 8), 5, true, "HIGH");
        assessment("EP-B2-W11-S", "PSN-B2", sg.getCode(), "SAFETY", 11, hour(78, 11), hour(78, 13),
                hour(86, 9), 6, true, "HIGH");

        // D: week-12 test exists but its medical adjudication arrives only on day 95 — after the legacy watermark
        assessment("EP-D4-W12-E", "PSN-D4", cn2.getCode(), "EFFICACY", 12, hour(85, 14), hour(85, 16),
                hour(95, 10), 17, true, "HIGH");

        // ---------------- 5. the immutable frozen monthly report ----------------
        String memberSummary = String.join("\n", List.of(
                "CN-01:CROSS_BORDER:LOW:2",
                "CN-01:CROSS_BORDER:MEDIUM:1",
                "CN-01:LOCAL_ONLY:MEDIUM:1",
                "CN-02:CROSS_BORDER:HIGH:2",
                "SG-01:CROSS_BORDER:HIGH:1",
                "SG-01:LOCAL_ONLY:HIGH:1",
                "OTHER-SITES:CROSS_BORDER:LOW:4"));
        FrozenMonthlyReport fmr = new FrozenMonthlyReport();
        fmr.setCode("FMR-2026-M05");
        fmr.setTitle("APEX-3 monthly endpoint report — 2026 May (legacy frozen plane)");
        fmr.setPeriodLabel("2026-M05");
        fmr.setSubmittedAt(DemoSeeder.day(SUBMIT_DAY).plus(9, java.time.temporal.ChronoUnit.HOURS));
        fmr.setFrozenAt(DemoSeeder.day(SUBMIT_DAY).plus(9, java.time.temporal.ChronoUnit.HOURS));
        fmr.setFrozenBy("global-statistics-team");
        fmr.setAnalysisAt(freezeAt);
        fmr.setLegacyDefinitionCode(legacy.getCode());
        fmr.setPinnedWatermark(eligibility.pinWatermarks(FREEZE_DAY));
        fmr.setStratumDenominators("LOW=4,MEDIUM=4,HIGH=4");
        fmr.setLegacyDenominatorTotal(12);
        fmr.setLegacyQualifiedTotal(8);
        fmr.setMemberSummary(memberSummary);
        fmr.setReportDigest(Text.sha256(fmr.getCode() + "|" + fmr.getLegacyQualifiedTotal() + "|"
                + fmr.getLegacyDenominatorTotal() + "|" + memberSummary));
        fmr.setImmutable(true);
        fmr.setNote("Submitted under the legacy frozen analysis plane; permanently retained. Comparison versions "
                + "may link to this report but must never rewrite, replace or recompute it.");
        frozenRepo.save(fmr);

        audit.record("system:seed", "COMPARISON_SCENARIO_SEEDED", "FrozenMonthlyReport", fmr.getCode(),
                "Two locked definitions, frozen monthly report, local endpoint watermarks and six dual-definition "
                        + "subject scenarios (A/B/C/D/E/F) seeded; comparison analysis day is " + COMPARISON_DAY + ".");
    }

    private AnalysisDefinition definition(String code, DefinitionKey key, String title, String version,
                                          int targetWeek, int windowStart, int windowEnd, boolean closest,
                                          boolean deviationExclusion, Instant from, Instant to, String note) {
        AnalysisDefinition d = new AnalysisDefinition();
        d.setCode(code);
        d.setDefinitionKey(key);
        d.setTitle(title);
        d.setDefinitionVersion(version);
        d.setTargetWeek(targetWeek);
        d.setWindowStartWeek(windowStart);
        d.setWindowEndWeek(windowEnd);
        d.setClosestToTarget(closest);
        d.setDeviationClass1Exclusion(deviationExclusion);
        d.setApplicableFrom(from);
        d.setApplicableTo(to);
        d.setLocked(true);
        d.setRegisteredAt(DemoSeeder.day(1));
        d.setRegisteredBy("registry-admin");
        d.setNote(note);
        d.setRulesDigest(Text.sha256(code + "|" + targetWeek + "|" + windowStart + "|" + windowEnd + "|"
                + closest + "|" + deviationExclusion + "|" + from + "|" + to));
        return definitionRepo.save(d);
    }

    private void grant(Subject s, Instant at) {
        ConsentEntry c = new ConsentEntry();
        c.setSubject(s);
        c.setKind(ConsentKind.GRANT);
        c.setPurposes("GLOBAL_ANALYSIS,CHINA_LOCAL_ANALYSIS,SAFETY_MONITORING");
        c.setFieldGroups("DEMOG,VISIT_VITALS,LABS,AE,IMAGING,RANDOMIZATION,DEVIATION");
        c.setRegions("EU,SG,DE,CN");
        c.setEffectiveFrom(at);
        c.setRecordedAt(at);
        c.setDocumentRef("ICF-" + s.getPseudonym() + "-2026");
        c.setNote("Cross-border grant for the definition-comparison scenario.");
        consentRepo.save(c);
    }

    private void endpointWatermark(String site, long confirmedSeq) {
        LocalEndpointWatermark w = new LocalEndpointWatermark();
        w.setSiteCode(site);
        w.setConfirmedSeq(confirmedSeq);
        w.setUpdatedAt(Instant.now());
        w.setNote("Local-plane endpoint ledger watermark; independent of the transfer/analysis watermarks.");
        endpointWatermarkRepo.save(w);
    }

    private void assessment(String stableId, String pseudonym, String site, String kind, int week,
                            Instant occurred, Instant received, Instant adjudicated, long seq,
                            boolean qualifying, String stratum) {
        EndpointAssessment a = new EndpointAssessment();
        a.setStableId(stableId);
        a.setRevision(1);
        a.setSubjectPseudonym(pseudonym);
        a.setSiteCode(site);
        a.setEndpointKind(kind);
        a.setStudyWeek(week);
        a.setOccurredAt(occurred);
        a.setReceivedAt(received);
        a.setAdjudicatedAt(adjudicated);
        a.setSourceSeq(seq);
        a.setQualifyingResult(qualifying);
        a.setRiskStratum(stratum);
        assessmentRepo.save(a);
    }

    private void deviation(String stableId, String pseudonym, String site, int deviationClass,
                           Instant occurred, Instant recorded, String note) {
        ProtocolDeviationEntry d = new ProtocolDeviationEntry();
        d.setStableId(stableId);
        d.setSubjectPseudonym(pseudonym);
        d.setSiteCode(site);
        d.setDeviationClass(deviationClass);
        d.setOccurredAt(occurred);
        d.setRecordedAt(recorded);
        d.setNote(note);
        deviationRepo.save(d);
    }

    private static Instant hour(int d, int h) {
        return DemoSeeder.hour(d, h);
    }
}
