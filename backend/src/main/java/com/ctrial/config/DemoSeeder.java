package com.ctrial.config;

import com.ctrial.model.*;
import com.ctrial.model.Enums.*;
import com.ctrial.repo.*;
import com.ctrial.service.AuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Seeds the acceptance scenario. Instants are derived from a fixed epoch so the
 * UI, the tests and this comment all speak of the same study days.
 *
 * <p>Study day 1 = 2026-03-02T09:00Z.
 * <pre>
 *  D02  B signs a global consent (global + local + safety purposes)
 *  D05  D's randomisation at CN-02 (inside the site's confirmed range)
 *  D10  A signs a China-only consent (local purpose only, region CN)
 *  D11  A's visit occurs; source accepts it D11, the wire only carries it D15
 *  D11  B's grade-3 AE occurs while global consent is in force
 *  D12  B withdraws the global purpose (signed D12 09:00, received D12 20:00)
 *  D12  D's visit occurs at CN-02, after that site's link stopped
 *  D13  C's imaging (v2.0 is approved but not yet effective)
 *  D14  protocol v2.0 becomes effective at CN-01 only, adding ADC/tumour-volume
 *  D15  C's second imaging carries the new fields
 *  D16  reference analysis day for every acceptance check
 * </pre>
 */
@Component
@Order(10)
public class DemoSeeder implements ApplicationRunner {

    public static final Instant EPOCH = Instant.parse("2026-03-02T09:00:00Z");

    private final StudyRepo studyRepo;
    private final SiteRepo siteRepo;
    private final SubjectRepo subjectRepo;
    private final ConsentRepo consentRepo;
    private final ProtocolRepo protocolRepo;
    private final EventRepo eventRepo;
    private final WatermarkRepo watermarkRepo;
    private final ApprovalRepo approvalRepo;
    private final DispositionRuleRepo ruleRepo;
    private final TieBreakRepo tieBreakRepo;
    private final ReceiptRepo receiptRepo;
    private final AuditService audit;
    private final boolean seed;

    public DemoSeeder(StudyRepo studyRepo, SiteRepo siteRepo, SubjectRepo subjectRepo, ConsentRepo consentRepo,
                      ProtocolRepo protocolRepo, EventRepo eventRepo, WatermarkRepo watermarkRepo,
                      ApprovalRepo approvalRepo, DispositionRuleRepo ruleRepo, TieBreakRepo tieBreakRepo,
                      ReceiptRepo receiptRepo, AuditService audit,
                      @Value("${app.demo.seed-on-startup:true}") boolean seed) {
        this.studyRepo = studyRepo;
        this.siteRepo = siteRepo;
        this.subjectRepo = subjectRepo;
        this.consentRepo = consentRepo;
        this.protocolRepo = protocolRepo;
        this.eventRepo = eventRepo;
        this.watermarkRepo = watermarkRepo;
        this.approvalRepo = approvalRepo;
        this.ruleRepo = ruleRepo;
        this.tieBreakRepo = tieBreakRepo;
        this.receiptRepo = receiptRepo;
        this.audit = audit;
        this.seed = seed;
    }

    public static Instant day(int d) {
        return EPOCH.plus(d - 1L, ChronoUnit.DAYS);
    }

    public static Instant hour(int d, int h) {
        return day(d).plus(h, ChronoUnit.HOURS);
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!seed || studyRepo.count() > 0) {
            return;
        }
        Study study = studyRepo.save(new Study("CT-2026-017", "APEX-3 multinational oncology study", EPOCH, "Helix Pharma"));

        Site cn1 = siteRepo.save(new Site("CN-01", "Shanghai Central Hospital", "CN", "CN-HUB-1", true));
        Site cn2 = siteRepo.save(new Site("CN-02", "Wuhan Union Hospital", "CN", "CN-HUB-2", true));
        Site sg = siteRepo.save(new Site("SG-01", "Singapore National Cancer Centre", "SG", "SEA-HUB-1", false));

        Subject a = subjectRepo.save(new Subject("PSN-A1", cn1, "CN", "CHN-001-0007"));
        Subject b = subjectRepo.save(new Subject("PSN-B2", sg, "SG", "SGP-004-0011"));
        Subject c = subjectRepo.save(new Subject("PSN-C3", cn1, "CN", "CHN-001-0009"));
        Subject d = subjectRepo.save(new Subject("PSN-D4", cn2, "CN", "CHN-002-0003"));

        // ---------------- protocols ----------------
        ProtocolVersion v1 = new ProtocolVersion();
        v1.setVersion("v1.2");
        v1.setTitle("APEX-3 original protocol");
        v1.setStatus(ProtocolStatus.APPROVED);
        v1.setEthicsApprovedAt(hour(1, 8));
        v1.setSponsorApprovedAt(hour(1, 9));
        v1.setEffectiveFrom(day(1));
        v1.setApplicableSites("");
        v1.setRequiredFieldGroups("DEMOG,VISIT_VITALS,LABS,AE,IMAGING,RANDOMIZATION,DEVIATION");
        v1.setNote("Baseline version governing every visit from day 1 at every site.");
        protocolRepo.save(v1);

        ProtocolVersion v2 = new ProtocolVersion();
        v2.setVersion("v2.0");
        v2.setTitle("APEX-3 amendment — quantitative imaging");
        v2.setStatus(ProtocolStatus.APPROVED);
        v2.setEthicsApprovedAt(hour(13, 10));
        v2.setSponsorApprovedAt(hour(13, 16));
        v2.setEffectiveFrom(day(14));
        v2.setApplicableSites("CN-01");
        v2.setRequiredFieldGroups("IMAGING,IMAGING_ADC,IMAGING_TUMOR_VOL");
        v2.setAmendsVersion("v1.2");
        v2.setNote("Adds ADC and tumour-volume imaging fields. Fully approved on day 13 but applies only from its "
                + "explicit effective date (day 14) and only at CN-01.");
        protocolRepo.save(v2);

        ProtocolVersion v3 = new ProtocolVersion();
        v3.setVersion("v3.0-draft");
        v3.setTitle("APEX-3 next amendment (sponsor approval outstanding)");
        v3.setStatus(ProtocolStatus.PARTIALLY_APPROVED);
        v3.setEthicsApprovedAt(hour(15, 11));
        v3.setSponsorApprovedAt(null);
        v3.setEffectiveFrom(day(20));
        v3.setApplicableSites("");
        v3.setRequiredFieldGroups("LABS,IMAGING_ADC");
        v3.setNote("Ethics approved, sponsor approval outstanding; anything that would fall under it stays PENDING.");
        protocolRepo.save(v3);

        // ---------------- approvals ----------------
        approval("DUA-GLOBAL-01", Purpose.GLOBAL_ANALYSIS, true, "EU,SG,DE,US",
                "CN-01,CN-02,SG-01",
                "DEMOG,VISIT_VITALS,LABS,AE,IMAGING,IMAGING_ADC,IMAGING_TUMOR_VOL,RANDOMIZATION,DEVIATION",
                "V1.2,V2.0", hour(2, 9), "Sponsor + EC (joint)",
                "Cross-border analysis envelope. Consent narrows this; it can never widen it.");

        approval("DUA-CN-LOCAL-01", Purpose.CHINA_LOCAL_ANALYSIS, false, "CN",
                "CN-01,CN-02",
                "DEMOG,VISIT_VITALS,LABS,AE,IMAGING,IMAGING_ADC,IMAGING_TUMOR_VOL,RANDOMIZATION,DEVIATION",
                "V1.2,V2.0", hour(2, 10), "China site EC",
                "China-resident source records default to this local envelope; no cross-border transfer.");

        approval("DUA-SG-LOCAL-01", Purpose.LOCAL_ANALYSIS, false, "SG",
                "SG-01",
                "DEMOG,VISIT_VITALS,LABS,AE,IMAGING,RANDOMIZATION,DEVIATION",
                "V1.2,V2.0", hour(2, 11), "Singapore NHG DSRB",
                "Local envelope for the SEA site.");

        // ---------------- disposition rules (registered before any withdrawal) ----------------
        DispositionRule retain = new DispositionRule();
        retain.setCode("DR-RETAIN-25Y");
        retain.setScope(DispositionScope.STUDY);
        retain.setAction(RuleAction.RETAIN_RESTRICTED);
        retain.setRetainYears(25);
        retain.setLegalBasis("ICH GCP 4.9.5 / EU CTR Art.28(3): trial records are retained 25 years after study "
                + "close for pharmacovigilance and inspection; after a withdrawal, access is restricted and the "
                + "derived rows are isolated rather than destroyed.");
        retain.setRegisteredAt(hour(2, 13));
        retain.setRegisteredBy("Sponsor + EC (joint)");
        ruleRepo.save(retain);

        // ---------------- consent ----------------
        consent(a, ConsentKind.GRANT, "CHINA_LOCAL_ANALYSIS",
                "DEMOG,VISIT_VITALS,LABS,AE,IMAGING,RANDOMIZATION,DEVIATION", "CN",
                hour(10, 10), hour(10, 14), "ICF-A-2026-0007",
                "Signed day 10 for China-only analysis. No global purpose is granted. Platform receipt 4h later.");
        consent(b, ConsentKind.GRANT, "GLOBAL_ANALYSIS,LOCAL_ANALYSIS,SAFETY_MONITORING",
                "DEMOG,VISIT_VITALS,LABS,AE,IMAGING,RANDOMIZATION,DEVIATION", "EU,SG,DE,CN",
                hour(2, 11), hour(2, 13), "ICF-B-2026-0011",
                "Initial grant covering global, local and safety purposes.");
        consent(b, ConsentKind.WITHDRAWAL, "GLOBAL_ANALYSIS", "", "EU,SG,DE,CN",
                hour(12, 9), hour(12, 20), "ICF-B-WD-2026-0011",
                "Withdraws the global-analysis purpose only; local analysis and safety monitoring remain. "
                        + "Local signature day 12 09:00; platform receipt not until day 12 20:00.");
        consent(c, ConsentKind.GRANT, "GLOBAL_ANALYSIS,CHINA_LOCAL_ANALYSIS",
                "DEMOG,VISIT_VITALS,LABS,AE,IMAGING,IMAGING_ADC,IMAGING_TUMOR_VOL,RANDOMIZATION,DEVIATION", "EU,SG,DE,CN",
                hour(1, 12), hour(1, 13), "ICF-C-2026-0009",
                "Broad grant; the protocol amendment decides which imaging fields actually apply.");
        consent(d, ConsentKind.GRANT, "GLOBAL_ANALYSIS,CHINA_LOCAL_ANALYSIS",
                "DEMOG,VISIT_VITALS,LABS,AE,IMAGING,RANDOMIZATION,DEVIATION", "EU,SG,DE,CN",
                hour(1, 14), hour(1, 15), "ICF-D-2026-0003",
                "Broad grant at the stalled CN-02 site.");

        // ---------------- events ----------------
        // CN-01 (healthy link) — sequences 11..15
        event("VST-A1-V1", a, Domain.VISIT, hour(11, 15), hour(11, 15), "CN-HUB-1", 11, "v1.2",
                "DEMOG,VISIT_VITALS", "{\"bp\":\"118/76\",\"hr\":74,\"tempC\":36.7}", hour(15, 10));
        event("LAB-A1-D11", a, Domain.LAB, hour(11, 16), hour(11, 16), "CN-HUB-1", 12, "v1.2",
                "LABS", "{\"wbc\":6.1,\"hgb\":12.8,\"plt\":241}", hour(15, 11));
        event("IMG-C3-D13", c, Domain.IMAGING, hour(13, 14), hour(13, 14), "CN-HUB-1", 13, "v1.2",
                "IMAGING", "{\"modality\":\"CT\",\"lesionMm\":22}", hour(13, 15));
        event("IMG-C3-D15", c, Domain.IMAGING, hour(15, 14), hour(15, 14), "CN-HUB-1", 14, "v2.0",
                "IMAGING,IMAGING_ADC,IMAGING_TUMOR_VOL",
                "{\"modality\":\"MRI\",\"adc\":0.94,\"tumorVolumeMl\":18.6}", hour(15, 16));
        event("AE-C3-01", c, Domain.AE, hour(12, 15), hour(12, 15), "CN-HUB-1", 15, "v1.2",
                "AE", "{\"term\":\"rash\",\"grade\":2,\"serious\":false}", hour(12, 16));

        // SG-01 (healthy link)
        event("AE-B2-01", b, Domain.AE, hour(11, 10), hour(11, 10), "SEA-HUB-1", 3, "v1.2",
                "AE", "{\"term\":\"neutropenia\",\"grade\":3,\"serious\":true}", hour(11, 12));
        event("VST-B2-V2", b, Domain.VISIT, hour(13, 11), hour(13, 11), "SEA-HUB-1", 4, "v1.2",
                "DEMOG,VISIT_VITALS", "{\"bp\":\"121/79\",\"hr\":81,\"tempC\":36.9}", hour(13, 13));

        // CN-02 (link stalled at day 9) — D's D5 record is inside the confirmed range,
        // D's D12 record is above it. Sequences 10 and 11 are genuinely absent (gap).
        event("RAND-D4-01", d, Domain.RANDOMIZATION, hour(5, 11), hour(5, 11), "CN-HUB-2", 8, "v1.2",
                "RANDOMIZATION", "{\"arm\":\"B\",\"strat\":\"PD-L1<1%\"}", hour(5, 11));
        event("VST-D4-V1", d, Domain.VISIT, hour(12, 10), hour(12, 10), "CN-HUB-2", 12, "v1.2",
                "DEMOG,VISIT_VITALS", "{\"bp\":\"126/82\",\"hr\":88,\"tempC\":37.1}", hour(12, 10));

        // ---------------- watermarks ----------------
        // CN-01 fully caught up (analysis folded through the last sequence)
        for (Domain dom : Domain.values()) {
            wm("CN-01", dom, 0, 0, 0, null);
        }
        advance("CN-01", Domain.VISIT, 11, 11, 11, hour(15, 10));
        advance("CN-01", Domain.LAB, 12, 12, 12, hour(15, 11));
        advance("CN-01", Domain.IMAGING, 14, 14, 14, hour(15, 16));
        advance("CN-01", Domain.AE, 15, 15, 15, hour(12, 16));

        // CN-02 link stopped after day 9: source has published through 12, transfer acked only 9
        for (Domain dom : Domain.values()) {
            wm("CN-02", dom, 0, 0, 0, null);
        }
        advance("CN-02", Domain.RANDOMIZATION, 9, 9, 9, hour(9, 23));
        advance("CN-02", Domain.VISIT, 12, 9, 9, hour(12, 10));
        for (Watermark w : watermarkRepo.findBySiteCodeOrderByDomainAsc("CN-02")) {
            w.setLinkBrokenAt(hour(9, 23));
            watermarkRepo.save(w);
        }

        // SG-01 healthy
        for (Domain dom : Domain.values()) {
            wm("SG-01", dom, 0, 0, 0, null);
        }
        advance("SG-01", Domain.AE, 3, 3, 3, hour(11, 12));
        advance("SG-01", Domain.VISIT, 4, 4, 4, hour(13, 13));

        audit.record("system:seed", "DEMO_SEEDED", "Study", study.getCode(),
                "Acceptance scenario A/B/C/D seeded; analysis day 16 is the reference instant. "
                        + "No tie-break rule is registered, so withdrawal/correction races must be quarantined.");
    }

    private void approval(String code, Purpose purpose, boolean crossBorder, String destinations, String sites,
                          String fields, String versions, Instant approvedAt, String by, String note) {
        DataUseApproval ap = new DataUseApproval();
        ap.setCode(code);
        ap.setPurpose(purpose);
        ap.setCrossBorder(crossBorder);
        ap.setDestinationRegions(destinations);
        ap.setSiteScope(sites);
        ap.setFieldGroups(fields);
        ap.setProtocolVersions(versions);
        ap.setObservationFrom(day(1));
        ap.setObservationTo(day(365));
        ap.setApprovedAt(approvedAt);
        ap.setApprovedBy(by);
        ap.setNote(note);
        approvalRepo.save(ap);
    }

    private void advance(String site, Domain domain, long source, long ack, long analysis, Instant at) {
        Watermark w = watermarkRepo.findBySiteCodeAndDomain(site, domain).orElseThrow();
        w.setSourceConfirmedSeq(source);
        w.setTransferAckSeq(ack);
        w.setAnalysisSeq(analysis);
        w.setSourceConfirmedAt(at);
        w.setAnalysisAt(at);
        w.setUpdatedAt(Instant.now());
        watermarkRepo.save(w);
    }

    private void consent(Subject s, ConsentKind kind, String purposes, String fields, String regions,
                         Instant effectiveFrom, Instant recordedAt, String doc, String note) {
        ConsentEntry c = new ConsentEntry();
        c.setSubject(s);
        c.setKind(kind);
        c.setPurposes(purposes);
        c.setFieldGroups(fields);
        c.setRegions(regions);
        c.setEffectiveFrom(effectiveFrom);
        c.setRecordedAt(recordedAt);
        c.setDocumentRef(doc);
        c.setNote(note);
        consentRepo.save(c);
    }

    private void event(String stableId, Subject s, Domain domain, Instant occurred, Instant received,
                       String source, long seq, String protocol, String fields, String payload, Instant ingested) {
        String normalized = com.ctrial.common.Text.csvStrings(java.util.Arrays.asList(fields.split(",")));
        String hash = com.ctrial.common.EventHash.of(stableId, 1, domain.name(), occurred, protocol,
                normalized, payload, false);
        LocalEvent e = new LocalEvent();
        e.setStableId(stableId);
        e.setRevision(1);
        e.setSubject(s);
        e.setDomain(domain);
        e.setOccurredAt(occurred);
        e.setReceivedAt(received);
        e.setIngestedAt(ingested);
        e.setSourceSystem(source);
        e.setSourceSeq(seq);
        e.setProtocolVersion(protocol);
        e.setFieldGroups(normalized);
        e.setPayloadJson(payload);
        e.setContentHash(hash);
        e = eventRepo.save(e);

        TransportReceipt receipt = new TransportReceipt();
        receipt.setSourceSystem(source);
        receipt.setDomain(domain.name());
        receipt.setSourceSeq(seq);
        receipt.setStableId(stableId);
        receipt.setRevision(1);
        receipt.setContentHash(hash);
        receipt.setAckedAt(ingested);
        receipt.setWasDuplicate(false);
        receipt.setEventId(e.getId());
        receiptRepo.save(receipt);
    }

    private void wm(String site, Domain domain, long source, long ack, long analysis, Instant at) {
        Watermark w = new Watermark();
        w.setSiteCode(site);
        w.setDomain(domain);
        w.setSourceConfirmedSeq(source);
        w.setTransferAckSeq(ack);
        w.setAnalysisSeq(analysis);
        w.setSourceConfirmedAt(at);
        w.setAnalysisAt(at);
        w.setUpdatedAt(Instant.now());
        watermarkRepo.save(w);
    }
}
