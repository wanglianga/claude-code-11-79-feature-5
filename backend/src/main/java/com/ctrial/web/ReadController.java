package com.ctrial.web;

import com.ctrial.model.*;
import com.ctrial.model.Enums.*;
import com.ctrial.repo.*;
import com.ctrial.service.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/** Read-oriented API used by the workspace screens. */
@RestController
@RequestMapping("/api")
public class ReadController {

    private final StudyRepo studyRepo;
    private final SiteRepo siteRepo;
    private final SubjectRepo subjectRepo;
    private final ConsentRepo consentRepo;
    private final ProtocolRepo protocolRepo;
    private final EventRepo eventRepo;
    private final WatermarkRepo watermarkRepo;
    private final ApprovalRepo approvalRepo;
    private final DispositionRuleRepo ruleRepo;
    private final DispositionExecutionRepo execRepo;
    private final QuarantineRepo quarantineRepo;
    private final RecoveryRepo recoveryRepo;
    private final TieBreakRepo tieBreakRepo;
    private final EligibilityRunRepo runRepo;
    private final DeliveryService deliveryService;
    private final DeliveryRecordRepo recordRepo;
    private final EligibilityService eligibility;
    private final StudyClock clock;
    private final AcceptanceService acceptance;
    private final AuditService audit;

    public ReadController(StudyRepo studyRepo, SiteRepo siteRepo, SubjectRepo subjectRepo, ConsentRepo consentRepo,
                          ProtocolRepo protocolRepo, EventRepo eventRepo, WatermarkRepo watermarkRepo,
                          ApprovalRepo approvalRepo, DispositionRuleRepo ruleRepo,
                          DispositionExecutionRepo execRepo, QuarantineRepo quarantineRepo,
                          RecoveryRepo recoveryRepo, TieBreakRepo tieBreakRepo, EligibilityRunRepo runRepo,
                          DeliveryService deliveryService, DeliveryRecordRepo recordRepo,
                          EligibilityService eligibility, StudyClock clock, AcceptanceService acceptance,
                          AuditService audit) {
        this.studyRepo = studyRepo;
        this.siteRepo = siteRepo;
        this.subjectRepo = subjectRepo;
        this.consentRepo = consentRepo;
        this.protocolRepo = protocolRepo;
        this.eventRepo = eventRepo;
        this.watermarkRepo = watermarkRepo;
        this.approvalRepo = approvalRepo;
        this.ruleRepo = ruleRepo;
        this.execRepo = execRepo;
        this.quarantineRepo = quarantineRepo;
        this.recoveryRepo = recoveryRepo;
        this.tieBreakRepo = tieBreakRepo;
        this.runRepo = runRepo;
        this.deliveryService = deliveryService;
        this.recordRepo = recordRepo;
        this.eligibility = eligibility;
        this.clock = clock;
        this.acceptance = acceptance;
        this.audit = audit;
    }

    @GetMapping("/session")
    public Map<String, Object> session(Authentication auth) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("authenticated", auth != null && auth.isAuthenticated());
        if (auth != null && auth.isAuthenticated()) {
            m.put("username", auth.getName());
            m.put("roles", auth.getAuthorities().stream().map(a -> a.getAuthority()).sorted().toList());
        }
        return m;
    }

    @GetMapping("/study")
    public Map<String, Object> study() {
        Study s = eligibility.study();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", s.getCode());
        m.put("name", s.getName());
        m.put("sponsor", s.getSponsor());
        m.put("epoch", s.getEpoch());
        m.put("sites", siteRepo.findAllByOrderByCodeAsc());
        m.put("subjects", subjectRepo.findAllByOrderByPseudonymAsc().stream().map(sub -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", sub.getId());
            x.put("pseudonym", sub.getPseudonym());
            x.put("region", sub.getRegion());
            x.put("site", sub.getSite().getCode());
            x.put("siteName", sub.getSite().getName());
            x.put("chinaResident", sub.getSite().isChinaResident());
            return x;
        }).toList());
        return m;
    }

    @GetMapping("/consents")
    public List<Map<String, Object>> consents() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ConsentEntry c : consentRepo.findAllByOrderByEffectiveFromAscRecordedAtAsc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("subject", c.getSubject().getPseudonym());
            m.put("site", c.getSubject().getSite().getCode());
            m.put("kind", c.getKind());
            m.put("purposes", c.getPurposes());
            m.put("fieldGroups", c.getFieldGroups());
            m.put("regions", c.getRegions());
            m.put("effectiveFrom", c.getEffectiveFrom());
            m.put("effectiveFromDay", clock.dayOf(eligibility.study(), c.getEffectiveFrom()));
            m.put("recordedAt", c.getRecordedAt());
            m.put("documentRef", c.getDocumentRef());
            m.put("note", c.getNote());
            out.add(m);
        }
        return out;
    }

    @GetMapping("/protocols")
    public List<Map<String, Object>> protocols() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProtocolVersion p : protocolRepo.findAllByOrderByEffectiveFromAsc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("version", p.getVersion());
            m.put("title", p.getTitle());
            m.put("status", p.getStatus());
            m.put("ethicsApprovedAt", p.getEthicsApprovedAt());
            m.put("sponsorApprovedAt", p.getSponsorApprovedAt());
            m.put("fullyApproved", p.fullyApproved());
            m.put("effectiveFrom", p.getEffectiveFrom());
            m.put("effectiveFromDay", clock.dayOf(eligibility.study(), p.getEffectiveFrom()));
            m.put("applicableSites", p.getApplicableSites());
            m.put("requiredFieldGroups", p.getRequiredFieldGroups());
            m.put("amendsVersion", p.getAmendsVersion());
            m.put("note", p.getNote());
            out.add(m);
        }
        return out;
    }

    @GetMapping("/approvals")
    public List<DataUseApproval> approvals() {
        return approvalRepo.findAllByOrderByApprovedAtAsc();
    }

    @GetMapping("/events")
    public List<Map<String, Object>> events() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (LocalEvent e : eventRepo.findAllByOrderByOccurredAtAscIdAsc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("stableId", e.getStableId());
            m.put("revision", e.getRevision());
            m.put("subject", e.getSubject().getPseudonym());
            m.put("site", e.getSubject().getSite().getCode());
            m.put("domain", e.getDomain());
            m.put("occurredAt", e.getOccurredAt());
            m.put("occurredDay", clock.dayOf(eligibility.study(), e.getOccurredAt()));
            m.put("receivedAt", e.getReceivedAt());
            m.put("ingestedAt", e.getIngestedAt());
            m.put("sourceSystem", e.getSourceSystem());
            m.put("sourceSeq", e.getSourceSeq());
            m.put("protocolVersion", e.getProtocolVersion());
            m.put("fieldGroups", e.getFieldGroups());
            m.put("supersedesEventId", e.getSupersedesEventId());
            m.put("voided", e.isVoided());
            m.put("contentHash", e.getContentHash());
            out.add(m);
        }
        return out;
    }

    @GetMapping("/watermarks")
    public Map<String, Object> watermarks(@RequestParam(defaultValue = "16") int day) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("day", day);
        m.put("rows", watermarkRepo.findAllByOrderBySiteCodeAscDomainAsc());
        m.put("coverage", eligibility.coverage(day));
        m.put("pin", eligibility.pinWatermarks(day));
        m.put("digest", eligibility.watermarkDigest(day));
        return m;
    }

    @GetMapping("/rules")
    public Map<String, Object> rules() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dispositionRules", ruleRepo.findAllByOrderByCodeAsc());
        m.put("executions", execRepo.findAllByOrderByCreatedAtDesc());
        m.put("tieBreakRules", tieBreakRepo.findAllByOrderByCodeAsc());
        return m;
    }

    @GetMapping("/quarantine")
    public List<QuarantineItem> quarantine() {
        return quarantineRepo.findAllByOrderByOpenedAtDesc();
    }

    @GetMapping("/recovery")
    public List<RecoveryCheck> recovery() {
        return recoveryRepo.findAllByOrderByRunAtDesc();
    }

    @GetMapping("/runs")
    public List<Map<String, Object>> runs() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (EligibilityRun r : runRepo.findAllByOrderByCreatedAtDesc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", r.getCode());
            m.put("analysisDay", r.getAnalysisDay());
            m.put("route", r.getRoute());
            m.put("status", r.getStatus());
            m.put("rowCount", r.getRowCount());
            m.put("globalCount", r.getGlobalCount());
            m.put("localCount", r.getLocalCount());
            m.put("pendingCount", r.getPendingCount());
            m.put("completenessOk", r.isCompletenessOk());
            m.put("setDigest", r.getSetDigest());
            m.put("createdAt", r.getCreatedAt());
            m.put("createdBy", r.getCreatedBy());
            m.put("staleReason", r.getStaleReason());
            out.add(m);
        }
        return out;
    }

    @GetMapping("/deliveries")
    public List<Delivery> deliveries() {
        return deliveryService.list();
    }

    @GetMapping("/deliveries/{code}")
    public ResponseEntity<?> delivery(@PathVariable String code) {
        Optional<Delivery> d = deliveryService.find(code);
        if (d.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("delivery", d.get());
        boolean readable = deliveryService.canRead(d.get());
        m.put("readable", readable);
        m.put("records", readable ? deliveryService.records(d.get()) : List.of());
        m.put("recordCount", recordRepo.countByDeliveryIdAndClearedFalse(d.get().getId()));
        m.put("summaryLines", d.get().getMemberSummary() == null ? List.of()
                : Arrays.stream(d.get().getMemberSummary().split("\n")).toList());
        return ResponseEntity.ok(m);
    }

    @GetMapping("/acceptance")
    public AcceptanceService.AcceptanceReport acceptance(@RequestParam(defaultValue = "16") int day) {
        return acceptance.report(day);
    }

    @GetMapping("/consents/timeline/{pseudonym}")
    public List<Map<String, Object>> timeline(@PathVariable String pseudonym) {
        return acceptance.consentTimeline(pseudonym);
    }

    @GetMapping("/audit")
    public List<AuditLog> audit() {
        return audit.recent();
    }
}
