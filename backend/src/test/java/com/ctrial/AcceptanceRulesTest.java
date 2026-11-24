package com.ctrial;

import com.ctrial.config.DemoSeeder;
import com.ctrial.model.*;
import com.ctrial.model.Enums.*;
import com.ctrial.repo.*;
import com.ctrial.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rule-level acceptance tests: the same engine that serves the API, exercised
 * in-process at analysis day 16. Each test names the requirement it proves.
 */
@SpringBootTest
@ActiveProfiles("test")
class AcceptanceRulesTest {

    @Autowired EligibilityService eligibility;
    @Autowired StudyClock clock;
    @Autowired SubjectRepo subjectRepo;
    @Autowired ConsentRepo consentRepo;
    @Autowired AcceptanceService acceptance;
    @Autowired EligibilityRunService runs;
    @Autowired RevocationService revocation;
    @Autowired EventIngestService ingest;
    @Autowired DeliveryService deliveries;
    @Autowired DeliveryRepo deliveryRepo;
    @Autowired DeliveryRecordRepo deliveryRecordRepo;
    @Autowired QuarantineRepo quarantineRepo;
    @Autowired TieBreakRepo tieBreakRepo;
    @Autowired WatermarkRepo watermarkRepo;
    @Autowired EventRepo eventRepo;

    private com.ctrial.service.EligibilityService.World world16() {
        return eligibility.loadWorld(clock.analysisInstant(eligibility.study(), 16));
    }

    private com.ctrial.service.dto.EvalResult eval(com.ctrial.service.EligibilityService.World w,
                                                   String pseudonym, String stableId, PlaneType plane) {
        LocalEvent e = w.events().stream()
                .filter(x -> x.getSubject().getPseudonym().equals(pseudonym) && x.getStableId().equals(stableId))
                .findFirst().orElseThrow();
        Purpose purpose = plane == PlaneType.GLOBAL ? Purpose.GLOBAL_ANALYSIS : localPurpose(pseudonym);
        String dest = plane == PlaneType.GLOBAL ? EligibilityService.GLOBAL_DESTINATION : e.getSubject().getRegion();
        return eligibility.evaluateOne(w, e, plane, purpose, dest);
    }

    private Purpose localPurpose(String pseudonym) {
        Subject s = subjectRepo.findByPseudonym(pseudonym).orElseThrow();
        return s.getSite().isChinaResident() ? Purpose.CHINA_LOCAL_ANALYSIS : Purpose.LOCAL_ANALYSIS;
    }

    @Test
    void subjectA_chinaOnlyConsent_blocksGlobalButKeepsLocal() {
        var w = world16();
        var g = eval(w, "PSN-A1", "VST-A1-V1", PlaneType.GLOBAL);
        var l = eval(w, "PSN-A1", "VST-A1-V1", PlaneType.LOCAL);
        assertThat(g.eligibility()).isNotEqualTo(Eligibility.GLOBAL_AVAILABLE);
        assertThat(g.reasonCodeCsv()).contains("CONSENT_NO_GRANT_FOR_PURPOSE");
        assertThat(g.reasonCodeCsv()).doesNotContain("WATERMARK_UNCONFIRMED");
        assertThat(l.eligibility()).isEqualTo(Eligibility.LOCAL_AVAILABLE);
    }

    @Test
    void subjectA_delayedUpload_doesNotChangeTheReason() {
        // the day-11 visit was ingested only on day 15, but the local consent, not the
        // upload timing, is what blocks it — the same code appears as above
        var g = eval(world16(), "PSN-A1", "VST-A1-V1", PlaneType.GLOBAL);
        assertThat(g.reasonCodeCsv()).contains("CONSENT_NO_GRANT_FOR_PURPOSE");
    }

    @Test
    void subjectB_eventBeforeWithdrawal_isHeldForRetentionButNotReusableGlobally() {
        var w = world16();
        var g = eval(w, "PSN-B2", "AE-B2-01", PlaneType.GLOBAL);
        assertThat(g.eligibility()).isEqualTo(Eligibility.BLOCKED);
        assertThat(g.reasonCodeCsv()).contains("CONSENT_HELD_AT_OCCURRENCE");
        assertThat(g.reasonCodeCsv()).contains("RETENTION_RESTRICTED_BASIS");
    }

    @Test
    void subjectB_visitAfterWithdrawal_hasNoBasis() {
        var w = world16();
        var g = eval(w, "PSN-B2", "VST-B2-V2", PlaneType.GLOBAL);
        assertThat(g.eligibility()).isEqualTo(Eligibility.BLOCKED);
        assertThat(g.reasonCodeCsv()).contains("CONSENT_WITHDRAWN_BEFORE_OCCURRENCE");
    }

    @Test
    void subjectB_localUseContinues() {
        var w = world16();
        assertThat(eval(w, "PSN-B2", "AE-B2-01", PlaneType.LOCAL).eligibility())
                .isEqualTo(Eligibility.LOCAL_AVAILABLE);
        assertThat(eval(w, "PSN-B2", "VST-B2-V2", PlaneType.LOCAL).eligibility())
                .isEqualTo(Eligibility.LOCAL_AVAILABLE);
    }

    @Test
    void subjectC_protocolAppliesOnlyFromItsEffectiveDay() {
        var w = world16();
        var d13 = eval(w, "PSN-C3", "IMG-C3-D13", PlaneType.GLOBAL);
        var d15 = eval(w, "PSN-C3", "IMG-C3-D15", PlaneType.GLOBAL);
        assertThat(d13.protocolVersion()).isEqualTo("v1.2");
        assertThat(d15.protocolVersion()).isEqualTo("v2.0");
        assertThat(d15.projectedCsv()).contains("IMAGING_ADC").contains("IMAGING_TUMOR_VOL");
    }

    @Test
    void subjectD_recordsAboveAnalysisWatermarkArePendingNotZero() {
        var w = world16();
        assertThat(eval(w, "PSN-D4", "RAND-D4-01", PlaneType.GLOBAL).eligibility())
                .isEqualTo(Eligibility.GLOBAL_AVAILABLE);
        var pending = eval(w, "PSN-D4", "VST-D4-V1", PlaneType.GLOBAL);
        assertThat(pending.eligibility()).isEqualTo(Eligibility.PENDING);
        assertThat(pending.reasonCodeCsv()).contains("WATERMARK_UNCONFIRMED");
        assertThat(eval(w, "PSN-D4", "VST-D4-V1", PlaneType.LOCAL).eligibility())
                .isEqualTo(Eligibility.LOCAL_AVAILABLE);
    }

    @Test
    void bothReconstructionRoutesAgreeOnTheLegalSet() {
        var eq = runs.equivalenceReadOnly(16);
        assertThat(eq.equal()).isTrue();
        assertThat(eq.differences()).isEmpty();
        assertThat(eq.materializedLegalKeys()).isEqualTo(eq.replayLegalKeys());
    }

    @Test
    void persistedComparisonAlsoAgrees() {
        var eq = runs.compare(16);
        assertThat(eq.equal()).isTrue();
        assertThat(eq.materializedLegal()).isEqualTo(eq.replayLegal());
    }

    @Test
    void acceptanceReportPasses() {
        var report = acceptance.report(16);
        assertThat(report.allPass())
                .withFailMessage(() -> report.checks().stream().filter(c -> !c.pass())
                        .map(c -> c.code() + ": expected " + c.expected() + " but saw " + c.observed())
                        .toList().toString())
                .isTrue();
    }

    @Test
    void staleSnapshotsDoNotAuthoriseNewReads() {
        EligibilityService.World w = world16();
        // materialise at day 16, then withdraw a grant covering C retroactively
        EligibilityRun run = runs.materialize(16, "test");
        assertThat(run.getStatus()).isEqualTo(SnapshotStatus.VALID);
        ConsentEntry wd = new ConsentEntry();
        wd.setSubject(subjectRepo.findByPseudonym("PSN-C3").orElseThrow());
        wd.setKind(ConsentKind.WITHDRAWAL);
        wd.setPurposes("GLOBAL_ANALYSIS");
        wd.setFieldGroups("");
        wd.setRegions("EU,SG,DE,CN");
        wd.setEffectiveFrom(DemoSeeder.hour(15, 23));
        wd.setRecordedAt(DemoSeeder.hour(15, 23));
        consentRepo.save(wd);
        int n = runs.invalidateRuns("CONSENT_WITHDRAWAL", wd.getEffectiveFrom(), "test", "PSN-C3");
        assertThat(n).isGreaterThan(0);
        assertThat(runs.findRun(run.getCode()).orElseThrow().getStatus()).isEqualTo(SnapshotStatus.STALE);
    }

    @Test
    void retransmissionDoesNotDoubleCount() {
        long eventsBefore = eventRepo.count();
        EventIngestService.IngestRequest req = new EventIngestService.IngestRequest(
                "LAB-C3-D16", 1, "PSN-C3", Domain.LAB, DemoSeeder.hour(16, 10), DemoSeeder.hour(16, 10),
                "CN-HUB-1", 40L, "v1.2", "LABS", "{\"wbc\":5.4,\"hgb\":13.1}", false, null);
        EventIngestService.IngestResult first = ingest.ingest(req);
        assertThat(first.duplicate()).isFalse();
        long afterFirst = eventRepo.count();
        EventIngestService.IngestResult second = ingest.ingest(req);
        assertThat(second.duplicate()).isTrue();
        assertThat(eventRepo.count()).isEqualTo(afterFirst);
        assertThat(eventRepo.count()).isEqualTo(eventsBefore + 1);
        assertThat(second.event().getId()).isEqualTo(first.event().getId());
    }

    @Test
    void conflictingDuplicateIsQuarantinedNotOverwritten() {
        long qBefore = quarantineRepo.count();
        EventIngestService.IngestRequest original = new EventIngestService.IngestRequest(
                "AE-C3-RACE", 1, "PSN-C3", Domain.AE, DemoSeeder.hour(16, 11), DemoSeeder.hour(16, 11),
                "CN-HUB-1", 41L, "v1.2", "AE", "{\"term\":\"fever\",\"grade\":1}", false, null);
        EventIngestService.IngestResult first = ingest.ingest(original);
        assertThat(first.duplicate()).isFalse();
        EventIngestService.IngestRequest conflicting = new EventIngestService.IngestRequest(
                "AE-C3-RACE", 1, "PSN-C3", Domain.AE, DemoSeeder.hour(16, 11), DemoSeeder.hour(16, 11),
                "CN-HUB-1", 41L, "v1.2", "AE", "{\"term\":\"fever\",\"grade\":4}", false, null);
        EventIngestService.IngestResult second = ingest.ingest(conflicting);
        assertThat(second.quarantined()).isTrue();
        assertThat(quarantineRepo.count()).isGreaterThan(qBefore);
        // the stored row keeps its original content
        assertThat(first.event().getPayloadJson()).contains("\"grade\":1");
    }

    @Test
    void withdrawalCorrectionRaceIsQuarantinedWithoutTieBreak() {
        // Withdrawals are seeded, and no tie-break rule is registered for this path.
        long before = quarantineRepo.count();
        // Craft a correction whose occurrence instant equals B's withdrawal effective instant.
        Instant withdrawalInstant = DemoSeeder.hour(12, 9);
        EventIngestService.IngestRequest req = new EventIngestService.IngestRequest(
                "AE-B2-01", 2, "PSN-B2", Domain.AE, withdrawalInstant, withdrawalInstant,
                "SEA-HUB-1", 99L, "v1.2", "AE", "{\"term\":\"neutropenia\",\"grade\":2,\"serious\":true}", false, null);
        EventIngestService.IngestResult r = ingest.ingest(req);
        assertThat(r.quarantined()).isTrue();
        assertThat(r.event()).isNull();
        assertThat(quarantineRepo.count()).isGreaterThan(before);
    }

    @Test
    void deliveryContainsOnlyProjectedFieldsAndSurvivesRevocationAsSummary() {
        Delivery d = deliveries.compile(11, Purpose.GLOBAL_ANALYSIS, "MATERIALIZED", "test");
        assertThat(d.getLegalMemberCount()).isEqualTo(2);
        List<DeliveryRecord> recs = deliveryRecordRepo.findByDeliveryIdOrderBySubjectPseudonymAscOccurredAtAsc(d.getId());
        assertThat(recs).anyMatch(r -> r.getSubjectPseudonym().equals("PSN-B2"));
        // no identity fields are ever projected
        assertThat(recs).allMatch(r -> r.getProjectedJson() == null || !r.getProjectedJson().contains("CHN-"));
        ConsentEntry wd = consentRepo.findAllByOrderByEffectiveFromAscRecordedAtAsc().stream()
                .filter(c -> c.getKind() == ConsentKind.WITHDRAWAL)
                .findFirst().orElseThrow();
        var outcome = revocation.propagateWithdrawal(wd.getId());
        assertThat(outcome.deliveryCodes()).contains(d.getCode());
        Delivery after = deliveryRepo.findByCode(d.getCode()).orElseThrow();
        // content access is revoked but the summary and digests are untouched
        assertThat(after.isAccessRevoked()).isTrue();
        assertThat(after.getStatus()).isEqualTo(DeliveryStatus.ACCESS_REVOKED);
        assertThat(after.getContentDigest()).isEqualTo(d.getContentDigest());
        assertThat(after.getLegalMemberDigest()).isEqualTo(d.getLegalMemberDigest());
        assertThat(after.getLegalMemberCount()).isEqualTo(d.getLegalMemberCount());
        assertThat(deliveries.canRead(after)).isFalse();
    }
}
