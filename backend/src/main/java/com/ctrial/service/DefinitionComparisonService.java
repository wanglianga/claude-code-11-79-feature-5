package com.ctrial.service;

import com.ctrial.common.Text;
import com.ctrial.model.*;
import com.ctrial.model.Enums.*;
import com.ctrial.repo.*;
import com.ctrial.service.DualDefinitionRules.SubjectEncoding;
import com.ctrial.service.DualDefinitionRules.SubjectFacts;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Orchestrates the whole definition-impact comparison subdomain:
 *
 * <ol>
 *   <li><b>dual-definition evaluator</b> — encodes legacy/revised
 *       qualification, not-evaluable and egress-block reasons locally on the
 *       same frozen records ({@link #evaluateSite});</li>
 *   <li><b>local-domain endpoint bridge</b> — turns the local rows into the
 *       only exportable artefact: reclassification counts, window-selection
 *       differences and uncertainty intervals ({@link #buildBridge});</li>
 *   <li><b>denominator contribution ledger</b> — per site × consent scope ×
 *       risk stratum denominator ranges ({@link #buildContributions});</li>
 *   <li><b>comparison publisher</b> — creates a new, separately watermarked
 *       {@link ComparisonVersion} that only links to the immutable frozen
 *       monthly report ({@link #publish});</li>
 *   <li><b>attribution workbench</b> — append-only annotations on aggregate
 *       differences ({@link #annotate}).</li>
 * </ol>
 *
 * Nothing in this class ever saves, updates or deletes a
 * {@link FrozenMonthlyReport}. Both bridge routes produce distinct versions;
 * the revised definition can never overwrite the frozen legacy conclusion.
 */
@Service
public class DefinitionComparisonService {

    /** Declared overlap stability floor: a cell below this is flagged insufficient. */
    public static final double OVERLAP_THRESHOLD = 0.60;
    /** Interval width above this fraction of the legacy denominator blocks a merged conclusion. */
    public static final double UNCERTAINTY_WIDTH_CAP = 0.25;
    /** Cohort route: excluding more than this fraction of evaluated subjects makes the standardisation unstable. */
    public static final double COHORT_EXCLUSION_CAP = 0.40;
    public static final List<String> STRATA = List.of("LOW", "MEDIUM", "HIGH");
    public static final List<String> ENDPOINT_KINDS = List.of("EFFICACY", "SAFETY");

    private final AnalysisDefinitionRepo definitionRepo;
    private final FrozenMonthlyReportRepo frozenRepo;
    private final ComparisonWatermarkRepo watermarkRepo;
    private final LocalEndpointWatermarkRepo endpointWatermarkRepo;
    private final EndpointAssessmentRepo assessmentRepo;
    private final ProtocolDeviationEntryRepo deviationRepo;
    private final ComparisonRunRepo runRepo;
    private final DualDefinitionRowRepo rowRepo;
    private final LocalBridgeAggregateRepo bridgeRepo;
    private final DenominatorContributionRepo contributionRepo;
    private final ComparisonVersionRepo versionRepo;
    private final DifferenceAnnotationRepo annotationRepo;
    private final SubjectRepo subjectRepo;
    private final SiteRepo siteRepo;
    private final ConsentRepo consentRepo;
    private final WatermarkRepo transferWatermarkRepo;
    private final EligibilityService eligibility;
    private final StudyClock clock;
    private final AuditService audit;

    public DefinitionComparisonService(AnalysisDefinitionRepo definitionRepo, FrozenMonthlyReportRepo frozenRepo,
                                       ComparisonWatermarkRepo watermarkRepo,
                                       LocalEndpointWatermarkRepo endpointWatermarkRepo,
                                       EndpointAssessmentRepo assessmentRepo,
                                       ProtocolDeviationEntryRepo deviationRepo, ComparisonRunRepo runRepo,
                                       DualDefinitionRowRepo rowRepo, LocalBridgeAggregateRepo bridgeRepo,
                                       DenominatorContributionRepo contributionRepo,
                                       ComparisonVersionRepo versionRepo, DifferenceAnnotationRepo annotationRepo,
                                       SubjectRepo subjectRepo, SiteRepo siteRepo, ConsentRepo consentRepo,
                                       WatermarkRepo transferWatermarkRepo, EligibilityService eligibility,
                                       StudyClock clock, AuditService audit) {
        this.definitionRepo = definitionRepo;
        this.frozenRepo = frozenRepo;
        this.watermarkRepo = watermarkRepo;
        this.endpointWatermarkRepo = endpointWatermarkRepo;
        this.assessmentRepo = assessmentRepo;
        this.deviationRepo = deviationRepo;
        this.runRepo = runRepo;
        this.rowRepo = rowRepo;
        this.bridgeRepo = bridgeRepo;
        this.contributionRepo = contributionRepo;
        this.versionRepo = versionRepo;
        this.annotationRepo = annotationRepo;
        this.subjectRepo = subjectRepo;
        this.siteRepo = siteRepo;
        this.consentRepo = consentRepo;
        this.transferWatermarkRepo = transferWatermarkRepo;
        this.eligibility = eligibility;
        this.clock = clock;
        this.audit = audit;
    }

    // ================================================================
    // 1. Registry + frozen baseline views
    // ================================================================

    @Transactional(readOnly = true)
    public List<AnalysisDefinition> definitions() {
        return definitionRepo.findAllByOrderByApplicableFromAsc();
    }

    @Transactional(readOnly = true)
    public List<FrozenMonthlyReport> frozenReports() {
        return frozenRepo.findAllByOrderByFrozenAtDesc();
    }

    @Transactional(readOnly = true)
    public List<ComparisonVersion> versions() {
        return versionRepo.findAllByOrderByPublishedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<ComparisonRun> runs() {
        return runRepo.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public Optional<ComparisonRun> findRun(String code) {
        return runRepo.findByCode(code);
    }

    @Transactional(readOnly = true)
    public Optional<ComparisonVersion> findVersion(String code) {
        return versionRepo.findByCode(code);
    }

    @Transactional(readOnly = true)
    public List<ComparisonWatermark> comparisonWatermarks() {
        return watermarkRepo.findAllByOrderByCapturedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<LocalEndpointWatermark> endpointWatermarks() {
        return endpointWatermarkRepo.findAllByOrderBySiteCodeAsc();
    }

    @Transactional(readOnly = true)
    public List<DualDefinitionRow> rowsOfRun(Long runId) {
        return rowRepo.findByComparisonRunIdOrderBySiteCodeAscSubjectPseudonymAsc(runId);
    }

    @Transactional(readOnly = true)
    public List<LocalBridgeAggregate> aggregatesOfVersion(Long versionId) {
        return bridgeRepo.findByComparisonVersionIdOrderBySiteCodeAscEndpointKindAsc(versionId);
    }

    @Transactional(readOnly = true)
    public List<DenominatorContribution> contributionsOfVersion(Long versionId) {
        return contributionRepo.findByComparisonVersionIdOrderBySiteCodeAscConsentScopeAscRiskStratumAsc(versionId);
    }

    // ================================================================
    // 2. Dual-definition evaluator (local plane)
    // ================================================================

    public record EvaluationSummary(String runCode, String siteCode, int subjectCount, int notEvaluable,
                                    int egressBlocked, String watermarkCode, String setDigest) {
    }

    @Transactional
    public EvaluationSummary evaluateSite(String frozenReportCode, String siteCode, BridgeRoute route,
                                          int analysisDay, String actor) {
        FrozenMonthlyReport frozen = frozenRepo.findByCode(frozenReportCode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown frozen report " + frozenReportCode));
        Site site = siteRepo.findByCode(siteCode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown site " + siteCode));
        AnalysisDefinition legacy = definitionRepo.findByDefinitionKey(DefinitionKey.LEGACY_W12).orElseThrow();
        AnalysisDefinition revised = definitionRepo.findByDefinitionKey(DefinitionKey.REVISED_W10_14).orElseThrow();

        Study study = eligibility.study();
        Instant analysisAt = clock.analysisInstant(study, analysisDay);
        long confirmedSeq = endpointWatermarkRepo.findBySiteCode(siteCode)
                .map(LocalEndpointWatermark::getConfirmedSeq).orElse(0L);

        ComparisonWatermark wm = captureWatermark(siteCode, frozenReportCode, confirmedSeq, analysisAt, actor);

        ComparisonRun run = new ComparisonRun();
        String runCode = nextRunCode(siteCode, route);
        run.setCode(runCode);
        run.setSiteCode(siteCode);
        run.setFrozenReportCode(frozenReportCode);
        run.setLegacyDefinitionCode(legacy.getCode());
        run.setRevisedDefinitionCode(revised.getCode());
        run.setRoute(route);
        run.setAnalysisAt(analysisAt);
        run.setWatermarkCode(wm.getCode());
        run.setCreatedAt(Instant.now());
        run.setCreatedBy(actor);
        run.setSetDigest(Text.sha256(runCode + "|pending"));
        run = runRepo.save(run);

        List<Subject> subjects = subjectRepo.findAllByOrderByPseudonymAsc().stream()
                .filter(s -> s.getSite().getCode().equals(siteCode))
                .toList();
        List<DualDefinitionRow> rows = new ArrayList<>();
        for (Subject s : subjects) {
            List<EndpointAssessment> assessments =
                    assessmentRepo.findBySubjectPseudonymOrderByStudyWeekAscOccurredAtAsc(s.getPseudonym());
            List<ProtocolDeviationEntry> deviations = deviationRepo.findBySubjectPseudonym(s.getPseudonym());
            boolean[] consent = crossBorderConsent(s, analysisAt);
            SubjectFacts facts = new SubjectFacts(s.getPseudonym(), siteCode, s.getRegion(),
                    stratumOf(s.getPseudonym(), assessments), site.isChinaResident(), assessments, deviations,
                    consent[0], consent[1], confirmedSeq, frozen.getAnalysisAt(), analysisAt, legacy, revised);
            SubjectEncoding enc = DualDefinitionRules.encode(facts);
            rows.add(toRow(run.getId(), s, site, facts, enc, analysisAt));
        }
        rowRepo.saveAll(rows);

        run.setSubjectCount(rows.size());
        run.setNotEvaluableCount((int) rows.stream().filter(DualDefinitionRow::isNotEvaluable).count());
        run.setEgressBlockedCount((int) rows.stream()
                .filter(r -> r.getEgressBlock() != EgressBlockReason.NONE).count());
        run.setSetDigest(Text.sha256(rows.stream().map(DualDefinitionRow::getRowHash).sorted()
                .collect(Collectors.joining("|"))));
        runRepo.save(run);

        audit.record(actor, "DUAL_DEFINITION_EVALUATED", "ComparisonRun", runCode,
                "Site " + siteCode + " evaluated " + rows.size() + " subjects under " + legacy.getCode() + " vs "
                        + revised.getCode() + " on independent watermark " + wm.getCode()
                        + "; notEvaluable=" + run.getNotEvaluableCount() + " egressBlocked="
                        + run.getEgressBlockedCount() + "; frozen report " + frozenReportCode + " untouched.");
        return new EvaluationSummary(runCode, siteCode, run.getSubjectCount(), run.getNotEvaluableCount(),
                run.getEgressBlockedCount(), wm.getCode(), run.getSetDigest());
    }

    private DualDefinitionRow toRow(Long runId, Subject s, Site site, SubjectFacts facts, SubjectEncoding enc,
                                    Instant evaluatedAt) {
        DualDefinitionRow r = new DualDefinitionRow();
        r.setComparisonRunId(runId);
        r.setSubjectPseudonym(s.getPseudonym());
        r.setSiteCode(site.getCode());
        r.setRegion(s.getRegion());
        r.setRiskStratum(facts.riskStratum());
        r.setChinaResident(site.isChinaResident());
        r.setLegacyQualification(enc.legacy().qualification());
        r.setLegacyReasons(String.join(",", enc.legacy().reasons()));
        r.setLegacySelectedTest(enc.legacy().selectedStableId());
        r.setLegacySelectedWeek(enc.legacy().selectedWeek());
        r.setRevisedQualification(enc.revised().qualification());
        r.setRevisedReasons(String.join(",", enc.revised().reasons()));
        r.setRevisedSelectedTest(enc.revised().selectedStableId());
        r.setRevisedSelectedWeek(enc.revised().selectedWeek());
        r.setRevisedDistanceWeeks(enc.revised().distanceWeeks());
        r.setWindowSelectionDiffers(enc.windowSelectionDiffers());
        r.setEgressBlock(enc.egressBlock());
        r.setEgressReasons(String.join(",", enc.egressReasons()));
        r.setNotEvaluable(enc.notEvaluable());
        r.setDualObservationOpportunity(enc.dualObservationOpportunity());
        r.setCommonCohortMember(enc.commonCohortMember());
        r.setReclassification(enc.reclassification());
        r.setEvaluatedAt(evaluatedAt);
        r.setRowHash(Text.sha256(runId + "|" + s.getPseudonym() + "|" + enc.legacy().qualification()
                + "|" + enc.revised().qualification() + "|" + enc.reclassification() + "|" + enc.egressBlock()
                + "|" + r.getRevisedSelectedTest()));
        return r;
    }

    /** @return {crossBorderGrantActive, crossBorderWithdrawalEffective} at the given instant. */
    private boolean[] crossBorderConsent(Subject subject, Instant at) {
        boolean grant = false;
        boolean withdrawal = false;
        for (ConsentEntry c : consentRepo.findBySubjectIdOrderByEffectiveFromAscRecordedAtAsc(subject.getId())) {
            boolean purposeHit = c.purposeSet().isEmpty()
                    || c.purposeSet().contains(Enums.Purpose.ALL)
                    || c.purposeSet().contains(Enums.Purpose.GLOBAL_ANALYSIS);
            if (!purposeHit) {
                continue;
            }
            boolean regionHit = c.regionSet().isEmpty()
                    || c.regionSet().stream().anyMatch(reg -> reg.covers(EligibilityService.GLOBAL_DESTINATION));
            if (!regionHit) {
                continue;
            }
            if (c.getKind() == ConsentKind.GRANT
                    && !c.getEffectiveFrom().isAfter(at)
                    && (c.getEffectiveTo() == null || c.getEffectiveTo().isAfter(at))) {
                grant = true;
            }
            if (c.getKind() == ConsentKind.WITHDRAWAL && !c.getEffectiveFrom().isAfter(at)) {
                withdrawal = true;
            }
        }
        return new boolean[]{grant, withdrawal};
    }

    private String stratumOf(String pseudonym, List<EndpointAssessment> assessments) {
        return assessments.stream().map(EndpointAssessment::getRiskStratum).filter(Objects::nonNull)
                .findFirst().orElse("MEDIUM");
    }

    private ComparisonWatermark captureWatermark(String siteCode, String frozenCode, long confirmedSeq,
                                                 Instant at, String actor) {
        String transferPin = transferWatermarkRepo.findBySiteCodeOrderByDomainAsc(siteCode).stream()
                .map(w -> w.getSiteCode() + "|" + w.getDomain() + "|src=" + w.getSourceConfirmedSeq()
                        + ",ack=" + w.getTransferAckSeq() + ",ana=" + w.getAnalysisSeq())
                .collect(Collectors.joining(";"));
        String pin = siteCode + "|ENDPOINT|confirmed=" + confirmedSeq + ";" + transferPin;
        ComparisonWatermark wm = new ComparisonWatermark();
        wm.setCode(nextWatermarkCode(siteCode));
        wm.setLabel("Independent comparison watermark — " + siteCode);
        wm.setPin(pin);
        wm.setDigest(Text.sha256(pin + "|" + at));
        wm.setLinkedFrozenReportCode(frozenCode);
        wm.setCapturedAt(Instant.now());
        wm.setCapturedBy(actor);
        wm.setNote("Separate counter plane; advancing it never moves the frozen report baseline.");
        return watermarkRepo.save(wm);
    }

    // ================================================================
    // 3 + 4. Bridge aggregates, denominator ledger and publisher
    // ================================================================

    @Transactional
    public ComparisonVersion publish(String runCode, String actor) {
        return publish(List.of(runCode), actor);
    }

    /**
     * Publish one comparison version from one or more local evaluation runs.
     * Cross-site composition is legal only when every run shares the same
     * route, frozen report and definition pair; each site contributes at most
     * one run. Each route yields its own separate version.
     */
    @Transactional
    public ComparisonVersion publish(List<String> runCodes, String actor) {
        if (runCodes == null || runCodes.isEmpty()) {
            throw new IllegalArgumentException("At least one comparison run is required");
        }
        List<ComparisonRun> runs = new ArrayList<>();
        for (String code : runCodes) {
            runs.add(runRepo.findByCode(code)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown comparison run " + code)));
        }
        ComparisonRun first = runs.get(0);
        Set<String> sites = new TreeSet<>();
        for (ComparisonRun run : runs) {
            if (run.getRoute() != first.getRoute()
                    || !run.getFrozenReportCode().equals(first.getFrozenReportCode())
                    || !run.getLegacyDefinitionCode().equals(first.getLegacyDefinitionCode())
                    || !run.getRevisedDefinitionCode().equals(first.getRevisedDefinitionCode())) {
                throw new IllegalArgumentException("Runs " + runCodes + " mix routes, frozen reports or definitions; "
                        + "a comparison version must stay on one route against one frozen baseline.");
            }
            if (!sites.add(run.getSiteCode())) {
                throw new IllegalArgumentException("Site " + run.getSiteCode()
                        + " has more than one run in this publish; evaluate once per site per version.");
            }
        }
        ComparisonRun run = first;
        FrozenMonthlyReport frozen = frozenRepo.findByCode(run.getFrozenReportCode()).orElseThrow();
        String frozenDigestBefore = frozen.getReportDigest();
        int frozenQualifiedBefore = frozen.getLegacyQualifiedTotal();

        List<DualDefinitionRow> allRows = new ArrayList<>();
        for (ComparisonRun r : runs) {
            allRows.addAll(rowRepo.findByComparisonRunIdOrderBySiteCodeAscSubjectPseudonymAsc(r.getId()));
        }
        List<DualDefinitionRow> inScope = run.getRoute() == BridgeRoute.COHORT_BRIDGE
                ? allRows.stream().filter(DualDefinitionRow::isCommonCohortMember).toList()
                : allRows;
        int cohortExcluded = run.getRoute() == BridgeRoute.COHORT_BRIDGE ? allRows.size() - inScope.size() : 0;

        ComparisonVersion version = new ComparisonVersion();
        version.setCode(nextVersionCode(run.getRoute()));
        version.setTitle("Definition-impact comparison — " + run.getRoute() + " — sites " + String.join(",", sites));
        version.setRoute(run.getRoute());
        version.setStatus(ComparisonStatus.PUBLISHED);
        version.setLinkedFrozenReportCode(frozen.getCode());
        version.setLegacyDefinitionCode(run.getLegacyDefinitionCode());
        version.setRevisedDefinitionCode(run.getRevisedDefinitionCode());
        version.setWatermarkCode(runs.stream().map(ComparisonRun::getWatermarkCode)
                .sorted().collect(Collectors.joining(",")));
        version.setAnalysisAt(run.getAnalysisAt());
        version.setOverlapThreshold(OVERLAP_THRESHOLD);
        version.setPublishedAt(Instant.now());
        version.setPublishedBy(actor);
        version.setBlockReasons("PENDING");
        version.setComparisonSummary("pending");
        version.setVersionDigest(Text.sha256(version.getCode() + "|pending"));
        version = versionRepo.save(version);

        // ---- local bridge aggregates: the only cross-border artefact, aggregate-only ----
        List<LocalBridgeAggregate> aggregates = new ArrayList<>();
        List<DenominatorContribution> contributions = new ArrayList<>();
        for (ComparisonRun r : runs) {
            List<DualDefinitionRow> runRows = inScope.stream()
                    .filter(x -> x.getSiteCode().equals(r.getSiteCode())).toList();
            aggregates.addAll(buildBridge(version.getId(), r, runRows));
            contributions.addAll(buildContributions(version.getId(), r, runRows, frozen));
        }
        bridgeRepo.saveAll(aggregates);
        contributionRepo.saveAll(contributions);

        // Version-level intervals use the headline EFFICACY endpoint; the SAFETY endpoint remains visible
        // per site in the per-kind bridge aggregates below.
        List<LocalBridgeAggregate> efficacyAggregates = aggregates.stream()
                .filter(a -> a.getEndpointKind().equals("EFFICACY")).toList();
        int point = efficacyAggregates.stream().mapToInt(LocalBridgeAggregate::getNewlyQualifiedCount).sum()
                + efficacyAggregates.stream().mapToInt(LocalBridgeAggregate::getRetainedCount).sum();
        int lower = efficacyAggregates.stream().mapToInt(LocalBridgeAggregate::getUncertaintyLower).sum();
        int upper = efficacyAggregates.stream().mapToInt(LocalBridgeAggregate::getUncertaintyUpper).sum();
        int denomLower = efficacyAggregates.stream().mapToInt(LocalBridgeAggregate::getDenominatorLower).sum();
        int denomUpper = efficacyAggregates.stream().mapToInt(LocalBridgeAggregate::getDenominatorUpper).sum();

        int localLegacyQualified = (int) allRows.stream()
                .filter(r -> r.getLegacyQualification() == EndpointQualification.QUALIFIED).count();

        version.setLegacyQualified(frozen.getLegacyQualifiedTotal());
        version.setLegacyDenominator(frozen.getLegacyDenominatorTotal());
        version.setRevisedQualifiedPoint(point);
        version.setRevisedQualifiedLower(lower);
        version.setRevisedQualifiedUpper(upper);
        version.setRevisedDenominatorLower(denomLower);
        version.setRevisedDenominatorUpper(denomUpper);
        version.setWindowSelectionDiffTotal((int) inScope.stream().filter(DualDefinitionRow::isWindowSelectionDiffers).count());
        version.setNotEvaluableTotal((int) allRows.stream().filter(DualDefinitionRow::isNotEvaluable).count());
        version.setEgressBlockedTotal((int) allRows.stream()
                .filter(r -> r.getEgressBlock() != EgressBlockReason.NONE).count());
        version.setInsufficientOverlapCells((int) contributions.stream()
                .filter(DenominatorContribution::isOverlapInsufficient).count());
        version.setCohortExcludedCount(cohortExcluded);
        // Coverage change is measured inside one evaluated scope; the frozen whole-study figure is only echoed.
        version.setLegacyCoverageDelta(point - localLegacyQualified);

        double widthRatio = frozen.getLegacyDenominatorTotal() == 0 ? 0.0
                : (double) (upper - lower) / frozen.getLegacyDenominatorTotal();
        version.setUncertaintyWidthRatio(widthRatio);

        List<String> blockReasons = new ArrayList<>();
        if (version.getInsufficientOverlapCells() > 0) {
            blockReasons.add("INSUFFICIENT_OVERLAP:" + version.getInsufficientOverlapCells() + " cell(s) below "
                    + OVERLAP_THRESHOLD);
        }
        if (widthRatio > UNCERTAINTY_WIDTH_CAP) {
            blockReasons.add("UNCERTAINTY_BAND_TOO_WIDE:" + String.format(Locale.ROOT, "%.2f", widthRatio)
                    + " > " + UNCERTAINTY_WIDTH_CAP);
        }
        if (version.getNotEvaluableTotal() > 0) {
            blockReasons.add("NOT_EVALUABLE_SUBJECTS:" + version.getNotEvaluableTotal());
        }
        if (run.getRoute() == BridgeRoute.COHORT_BRIDGE && !allRows.isEmpty()) {
            double excludedRatio = (double) cohortExcluded / allRows.size();
            if (excludedRatio > COHORT_EXCLUSION_CAP) {
                blockReasons.add("COHORT_WEIGHT_UNSTABLE:excludedRatio="
                        + String.format(Locale.ROOT, "%.2f", excludedRatio) + " > " + COHORT_EXCLUSION_CAP
                        + " (common-observation requirement leaves too few subjects for stable standardisation)");
            }
        }
        version.setMergeBlocked(!blockReasons.isEmpty());
        version.setBlockReasons(blockReasons.isEmpty() ? "NONE" : String.join(";", blockReasons));
        if (version.isMergeBlocked()) {
            version.setStatus(ComparisonStatus.PUBLISHED_BLOCKED);
        }

        List<String> summary = new ArrayList<>();
        summary.add("link=" + frozen.getCode() + " (read-only; legacy figures echoed, never recomputed)");
        summary.add("route=" + run.getRoute() + " watermark=" + version.getWatermarkCode());
        for (LocalBridgeAggregate a : aggregates) {
            summary.add(a.getSiteCode() + ":" + a.getEndpointKind() + " retained=" + a.getRetainedCount()
                    + " newlyQualified=" + a.getNewlyQualifiedCount() + " lost=" + a.getLostCount()
                    + " indeterminate=" + a.getIndeterminateCount() + " windowDiff="
                    + a.getWindowSelectionDiffCount() + " qualifiedInterval=[" + a.getUncertaintyLower()
                    + "," + a.getUncertaintyUpper() + "] denominatorInterval=[" + a.getDenominatorLower()
                    + "," + a.getDenominatorUpper() + "] egressBlocked=" + a.getEgressBlockedCount());
        }
        version.setComparisonSummary(String.join("\n", summary));
        version.setVersionDigest(Text.sha256(summary.stream().sorted().collect(Collectors.joining("|"))
                + "|" + version.getBlockReasons()));
        versionRepo.save(version);
        for (LocalBridgeAggregate a : aggregates) {
            a.setComparisonVersionId(version.getId());
        }
        bridgeRepo.saveAll(aggregates);

        // hard proof that the frozen baseline was not modified by the publish
        FrozenMonthlyReport after = frozenRepo.findByCode(frozen.getCode()).orElseThrow();
        if (!Objects.equals(after.getReportDigest(), frozenDigestBefore)
                || after.getLegacyQualifiedTotal() != frozenQualifiedBefore || !after.isImmutable()) {
            throw new IllegalStateException("Frozen monthly report " + frozen.getCode()
                    + " was modified during comparison publishing; operation refused.");
        }

        audit.record(actor, "COMPARISON_VERSION_PUBLISHED", "ComparisonVersion", version.getCode(),
                "Route " + run.getRoute() + "; links (never modifies) " + frozen.getCode()
                        + "; revised qualified interval [" + lower + "," + upper + "]; mergeBlocked="
                        + version.isMergeBlocked() + " (" + version.getBlockReasons() + ").");
        return version;
    }

    private List<LocalBridgeAggregate> buildBridge(Long versionId, ComparisonRun run, List<DualDefinitionRow> rows) {
        List<LocalBridgeAggregate> out = new ArrayList<>();
        for (String kind : ENDPOINT_KINDS) {
            List<DualDefinitionRow> kindRows = rowsForKind(rows, kind);
            if (kindRows.isEmpty()) {
                continue;
            }
            LocalBridgeAggregate a = new LocalBridgeAggregate();
            a.setComparisonVersionId(versionId);
            a.setComparisonRunId(run.getId());
            a.setSiteCode(run.getSiteCode());
            a.setEndpointKind(kind);
            a.setRetainedCount(count(kindRows, ReclassificationKind.RETAINED));
            a.setNewlyQualifiedCount(count(kindRows, ReclassificationKind.NEWLY_QUALIFIED));
            a.setLostCount(count(kindRows, ReclassificationKind.LOST));
            a.setNeverQualifiedCount(count(kindRows, ReclassificationKind.NEVER_QUALIFIED));
            a.setIndeterminateCount(count(kindRows, ReclassificationKind.INDETERMINATE));
            a.setWindowSelectionDiffCount((int) kindRows.stream().filter(DualDefinitionRow::isWindowSelectionDiffers).count());
            a.setEgressBlockedCount((int) kindRows.stream()
                    .filter(r -> r.getEgressBlock() != EgressBlockReason.NONE).count());
            int point = a.getRetainedCount() + a.getNewlyQualifiedCount();
            int unresolved = a.getIndeterminateCount() + a.getEgressBlockedCount();
            a.setUncertaintyLower(point);
            a.setUncertaintyUpper(point + unresolved);
            int denomPoint = (int) kindRows.stream()
                    .filter(r -> r.getRevisedQualification() != EndpointQualification.EXCLUDED_DEVIATION).count();
            a.setDenominatorLower(denomPoint);
            a.setDenominatorUpper(denomPoint + unresolved);
            a.setEmittedAt(Instant.now());
            a.setAggregateDigest(Text.sha256(run.getSiteCode() + "|" + kind + "|" + a.getRetainedCount()
                    + "|" + a.getNewlyQualifiedCount() + "|" + a.getLostCount() + "|" + a.getIndeterminateCount()
                    + "|" + a.getUncertaintyLower() + "|" + a.getUncertaintyUpper()));
            out.add(a);
        }
        return out;
    }

    /** A row contributes to a kind's aggregate when the subject has an assessment of that kind. */
    private List<DualDefinitionRow> rowsForKind(List<DualDefinitionRow> rows, String kind) {
        Set<String> subjectsWithKind = assessmentRepo.findAllByOrderBySubjectPseudonymAscStudyWeekAsc().stream()
                .filter(a -> a.getEndpointKind().equals(kind))
                .map(EndpointAssessment::getSubjectPseudonym)
                .collect(Collectors.toSet());
        return rows.stream().filter(r -> subjectsWithKind.contains(r.getSubjectPseudonym())).toList();
    }

    private int count(List<DualDefinitionRow> rows, ReclassificationKind kind) {
        return (int) rows.stream().filter(r -> r.getReclassification() == kind).count();
    }

    private List<DenominatorContribution> buildContributions(Long versionId, ComparisonRun run,
                                                             List<DualDefinitionRow> rows,
                                                             FrozenMonthlyReport frozen) {
        Map<String, Integer> legacyCells = parseLegacyCells(frozen);
        int legacyTotal = Math.max(1, frozen.getLegacyDenominatorTotal());
        List<DenominatorContribution> out = new ArrayList<>();
        for (String scope : List.of("CROSS_BORDER", "LOCAL_ONLY")) {
            for (String stratum : STRATA) {
                final String sc = scope;
                List<DualDefinitionRow> cell = rows.stream()
                        .filter(r -> r.getRiskStratum().equals(stratum))
                        .filter(r -> scope.equals(consentScope(r)))
                        .toList();
                if (cell.isEmpty() && !legacyCells.containsKey(run.getSiteCode() + "|" + scope + "|" + stratum)) {
                    continue;
                }
                int legacyN = legacyCells.getOrDefault(run.getSiteCode() + "|" + scope + "|" + stratum, 0);
                int point = (int) cell.stream()
                        .filter(r -> r.getRevisedQualification() != EndpointQualification.EXCLUDED_DEVIATION).count();
                int unresolved = (int) cell.stream()
                        .filter(r -> r.isNotEvaluable() || r.getEgressBlock() != EgressBlockReason.NONE).count();
                long overlap = cell.stream().filter(DualDefinitionRow::isDualObservationOpportunity).count();
                double overlapFraction = cell.isEmpty() ? 0.0 : (double) overlap / cell.size();
                double weight = run.getRoute() == BridgeRoute.COHORT_BRIDGE
                        ? (double) legacyN / legacyTotal
                        : 1.0;

                DenominatorContribution dc = new DenominatorContribution();
                dc.setComparisonVersionId(versionId);
                dc.setSiteCode(run.getSiteCode());
                dc.setConsentScope(sc);
                dc.setRiskStratum(stratum);
                dc.setLegacyDenominator(legacyN);
                dc.setRevisedDenominatorPoint(point);
                dc.setDenominatorLower(point);
                dc.setDenominatorUpper(point + unresolved);
                dc.setOverlapFraction(overlapFraction);
                dc.setOverlapInsufficient(overlapFraction < OVERLAP_THRESHOLD);
                dc.setStandardisationWeight(weight);
                dc.setNote(run.getRoute() == BridgeRoute.COHORT_BRIDGE
                        ? "Standardised to the frozen report's risk-stratum mix (weight "
                                + String.format(Locale.ROOT, "%.3f", weight) + "); weight-stability constraint applies."
                        : "Direct subject-rule correspondence; no standardisation weight.");
                out.add(dc);
            }
        }
        return out;
    }

    private String consentScope(DualDefinitionRow r) {
        return r.getEgressBlock() == EgressBlockReason.NO_CROSS_BORDER_CONSENT
                || r.getEgressBlock() == EgressBlockReason.CONSENT_WITHDRAWN ? "LOCAL_ONLY" : "CROSS_BORDER";
    }

    private Map<String, Integer> parseLegacyCells(FrozenMonthlyReport frozen) {
        Map<String, Integer> out = new HashMap<>();
        for (String line : frozen.getMemberSummary().split("\n")) {
            String[] parts = line.split(":");
            if (parts.length >= 4) {
                out.put(parts[0] + "|" + parts[1] + "|" + parts[2], Integer.parseInt(parts[3]));
            }
        }
        return out;
    }

    // ================================================================
    // 5. Attribution workbench
    // ================================================================

    public record AnnotationRequest(Long versionId, String siteCode, String endpointKind, String differenceKind,
                                    String explanation, String attributedTo) {
    }

    @Transactional
    public DifferenceAnnotation annotate(AnnotationRequest req, String actor) {
        ComparisonVersion v = versionRepo.findById(req.versionId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown comparison version id " + req.versionId()));
        if (req.explanation() == null || req.explanation().isBlank() || req.differenceKind() == null) {
            throw new IllegalArgumentException("differenceKind and explanation are required");
        }
        DifferenceAnnotation a = new DifferenceAnnotation();
        a.setComparisonVersionId(v.getId());
        a.setSiteCode(req.siteCode() == null ? "" : req.siteCode());
        a.setEndpointKind(req.endpointKind() == null ? "ALL" : req.endpointKind());
        a.setDifferenceKind(req.differenceKind());
        a.setExplanation(req.explanation());
        a.setAttributedTo(req.attributedTo() == null ? actor : req.attributedTo());
        a.setCreatedAt(Instant.now());
        a.setCreatedBy(actor);
        a = annotationRepo.save(a);
        audit.record(actor, "DIFFERENCE_ANNOTATED", "DifferenceAnnotation", String.valueOf(a.getId()),
                "Version " + v.getCode() + " " + a.getDifferenceKind() + " at " + a.getSiteCode()
                        + "/" + a.getEndpointKind() + ": " + a.getExplanation());
        return a;
    }

    @Transactional(readOnly = true)
    public List<DifferenceAnnotation> annotations(Long versionId) {
        return annotationRepo.findByComparisonVersionIdOrderByCreatedAtAsc(versionId);
    }

    // ================================================================
    // code generators
    // ================================================================

    private String nextRunCode(String site, BridgeRoute route) {
        String prefix = "CR-" + site + "-" + (route == BridgeRoute.COHORT_BRIDGE ? "COHORT" : "DUAL");
        long n = runRepo.findAll().stream().filter(r -> r.getCode().startsWith(prefix)).count();
        return prefix + "-" + String.format("%03d", n + 1);
    }

    private String nextWatermarkCode(String site) {
        String prefix = "CWM-" + site;
        long n = watermarkRepo.findAll().stream().filter(w -> w.getCode().startsWith(prefix)).count();
        return prefix + "-" + String.format("%03d", n + 1);
    }

    private String nextVersionCode(BridgeRoute route) {
        String prefix = "CMP-" + (route == BridgeRoute.COHORT_BRIDGE ? "COHORT" : "DUAL");
        long n = versionRepo.findAll().stream().filter(v -> v.getCode().startsWith(prefix)).count();
        return prefix + "-" + String.format("%03d", n + 1);
    }
}
