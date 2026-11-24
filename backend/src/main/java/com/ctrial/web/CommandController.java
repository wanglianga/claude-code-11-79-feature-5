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

/** Command API: consent/protocol entry, event ingest, watermarks, deliveries, revocation, recovery. */
@RestController
@RequestMapping("/api")
public class CommandController {

    private final ConsentRepo consentRepo;
    private final SubjectRepo subjectRepo;
    private final ProtocolRepo protocolRepo;
    private final ApprovalRepo approvalRepo;
    private final DispositionRuleRepo ruleRepo;
    private final TieBreakRepo tieBreakRepo;
    private final EventIngestService ingest;
    private final StateService state;
    private final EligibilityRunService runs;
    private final DeliveryService deliveries;
    private final RevocationService revocation;
    private final RecoveryService recovery;
    private final QuarantineService quarantine;
    private final EligibilityService eligibility;
    private final StudyClock clock;
    private final AuditService audit;

    public CommandController(ConsentRepo consentRepo, SubjectRepo subjectRepo, ProtocolRepo protocolRepo,
                             ApprovalRepo approvalRepo, DispositionRuleRepo ruleRepo, TieBreakRepo tieBreakRepo,
                             EventIngestService ingest, StateService state, EligibilityRunService runs,
                             DeliveryService deliveries, RevocationService revocation, RecoveryService recovery,
                             QuarantineService quarantine, EligibilityService eligibility, StudyClock clock,
                             AuditService audit) {
        this.consentRepo = consentRepo;
        this.subjectRepo = subjectRepo;
        this.protocolRepo = protocolRepo;
        this.approvalRepo = approvalRepo;
        this.ruleRepo = ruleRepo;
        this.tieBreakRepo = tieBreakRepo;
        this.ingest = ingest;
        this.state = state;
        this.runs = runs;
        this.deliveries = deliveries;
        this.revocation = revocation;
        this.recovery = recovery;
        this.quarantine = quarantine;
        this.eligibility = eligibility;
        this.clock = clock;
        this.audit = audit;
    }

    private String actor(Authentication auth) {
        return auth == null ? "anonymous" : auth.getName();
    }

    // ---------------- consent ----------------

    public record ConsentRequest(String pseudonym, ConsentKind kind, List<String> purposes,
                                 List<String> fieldGroups, List<String> regions,
                                 Instant effectiveFrom, Instant recordedAt, Instant effectiveTo,
                                 String documentRef, String note) {
    }

    @PostMapping("/consents")
    public ResponseEntity<?> addConsent(@RequestBody ConsentRequest req, Authentication auth) {
        Subject subject = subjectRepo.findByPseudonym(req.pseudonym())
                .orElseThrow(() -> new IllegalArgumentException("Unknown pseudonym " + req.pseudonym()));
        if (req.kind() == null || req.effectiveFrom() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "kind and effectiveFrom are required"));
        }
        ConsentEntry c = new ConsentEntry();
        c.setSubject(subject);
        c.setKind(req.kind());
        c.setPurposes(String.join(",", req.purposes() == null ? List.of() : req.purposes()));
        c.setFieldGroups(String.join(",", req.fieldGroups() == null ? List.of() : req.fieldGroups()));
        c.setRegions(String.join(",", req.regions() == null ? List.of() : req.regions()));
        c.setEffectiveFrom(req.effectiveFrom());
        c.setEffectiveTo(req.effectiveTo());
        c.setRecordedAt(req.recordedAt() == null ? Instant.now() : req.recordedAt());
        c.setDocumentRef(req.documentRef());
        c.setNote(req.note());
        c = consentRepo.save(c);
        audit.record(actor(auth), "CONSENT_ADDED", "ConsentEntry", String.valueOf(c.getId()),
                subject.getPseudonym() + " " + c.getKind() + " effective " + c.getEffectiveFrom()
                        + " (local time), received " + c.getRecordedAt());

        int invalidated = 0;
        if (c.getKind() == ConsentKind.WITHDRAWAL) {
            invalidated = runs.invalidateRunsAffectedBy("CONSENT_WITHDRAWAL", c.getEffectiveFrom(),
                    subject.getPseudonym() + " withdrew " + c.getPurposes());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entry", c);
        out.put("runsInvalidated", invalidated);
        out.put("next", c.getKind() == ConsentKind.WITHDRAWAL
                ? "Call /api/revocations/propagate to revoke access to existing deliveries and apply the registered disposition."
                : "Materialise or replay the current analysis day to pick up the change.");
        return ResponseEntity.ok(out);
    }

    // ---------------- protocols ----------------

    public record ProtocolRequest(String version, String title, ProtocolStatus status, Instant ethicsApprovedAt,
                                  Instant sponsorApprovedAt, Instant effectiveFrom, List<String> applicableSites,
                                  List<String> requiredFieldGroups, String amendsVersion, String note) {
    }

    @PostMapping("/protocols")
    public ResponseEntity<?> addProtocol(@RequestBody ProtocolRequest req, Authentication auth) {
        ProtocolVersion p = protocolRepo.findByVersion(req.version()).orElseGet(ProtocolVersion::new);
        p.setVersion(req.version());
        p.setTitle(req.title());
        p.setStatus(req.status() == null ? ProtocolStatus.DRAFT : req.status());
        p.setEthicsApprovedAt(req.ethicsApprovedAt());
        p.setSponsorApprovedAt(req.sponsorApprovedAt());
        p.setEffectiveFrom(req.effectiveFrom());
        p.setApplicableSites(String.join(",", req.applicableSites() == null ? List.of() : req.applicableSites()));
        p.setRequiredFieldGroups(String.join(",", req.requiredFieldGroups() == null ? List.of() : req.requiredFieldGroups()));
        p.setAmendsVersion(req.amendsVersion());
        p.setNote(req.note());
        p = protocolRepo.save(p);
        audit.record(actor(auth), "PROTOCOL_UPSERTED", "ProtocolVersion", p.getVersion(),
                "status=" + p.getStatus() + " effective=" + p.getEffectiveFrom());

        int invalidated = runs.invalidateRunsAffectedBy("PROTOCOL_CHANGE", p.getEffectiveFrom(),
                "protocol " + p.getVersion() + " now effective from " + p.getEffectiveFrom()
                        + " (approval alone does not apply it early)");
        return ResponseEntity.ok(Map.of("protocol", p, "runsInvalidated", invalidated));
    }

    // ---------------- events ----------------

    @PostMapping("/events")
    public ResponseEntity<?> ingestEvent(@RequestBody EventIngestService.IngestRequest req, Authentication auth) {
        EventIngestService.IngestResult r = ingest.ingest(req);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("duplicate", r.duplicate());
        out.put("quarantined", r.quarantined());
        out.put("pendingAtSource", r.pendingAtSource());
        out.put("message", r.message());
        out.put("event", r.event());
        return ResponseEntity.ok(out);
    }

    // ---------------- watermark operations ----------------

    public record WatermarkOp(String site, Domain domain, Long seq, String reason) {
    }

    @PostMapping("/watermarks/confirm-source")
    public Watermark confirmSource(@RequestBody WatermarkOp op, Authentication auth) {
        return state.confirmSource(op.site(), op.domain(), op.seq(), actor(auth));
    }

    @PostMapping("/watermarks/ack-transfer")
    public Watermark ackTransfer(@RequestBody WatermarkOp op, Authentication auth) {
        return state.ackTransfer(op.site(), op.domain(), op.seq(), actor(auth));
    }

    @PostMapping("/watermarks/hold-transfer")
    public Watermark holdTransfer(@RequestBody WatermarkOp op, Authentication auth) {
        return state.holdTransfer(op.site(), op.domain(), op.seq(), actor(auth));
    }

    @PostMapping("/watermarks/fold-analysis")
    public Watermark foldAnalysis(@RequestBody WatermarkOp op, Authentication auth) {
        Instant at = clock.analysisInstant(eligibility.study(), clock.dayOf(eligibility.study(), Instant.now()));
        return state.foldAnalysis(op.site(), op.domain(), op.seq(), at, actor(auth));
    }

    @PostMapping("/watermarks/break-link")
    public Map<String, Object> breakLink(@RequestBody WatermarkOp op, Authentication auth) {
        state.breakLink(op.site(), actor(auth), op.reason() == null ? "manual" : op.reason());
        return Map.of("site", op.site(), "linkBroken", true);
    }

    @PostMapping("/watermarks/restore-link")
    public Map<String, Object> restoreLink(@RequestBody WatermarkOp op, Authentication auth) {
        state.restoreLink(op.site(), actor(auth));
        return Map.of("site", op.site(), "linkRestored", true,
                "next", "Run the recovery check for this site before trusting any gap.");
    }

    // ---------------- eligibility snapshots ----------------

    @PostMapping("/runs/materialize")
    public EligibilityRun materialize(@RequestParam(defaultValue = "16") int day, Authentication auth) {
        return runs.materialize(day, actor(auth));
    }

    @GetMapping("/runs/replay")
    public EligibilityRunService.ReplayResult replay(@RequestParam(defaultValue = "16") int day) {
        return runs.replay(day);
    }

    @GetMapping("/runs/compare")
    public EligibilityRunService.Equivalence compare(@RequestParam(defaultValue = "16") int day) {
        return runs.compare(day);
    }

    @GetMapping("/runs/{code}/rows")
    public ResponseEntity<?> rows(@PathVariable String code, @RequestParam(required = false) PlaneType plane) {
        Optional<EligibilityRun> run = runs.findRun(code);
        if (run.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("run", run.get());
        m.put("rows", plane == null ? runs.rows(code) : runs.rows(code, plane));
        return ResponseEntity.ok(m);
    }

    // ---------------- deliveries ----------------

    public record DeliveryRequest(Integer day, Purpose purpose, String route) {
    }

    @PostMapping("/deliveries/compile")
    public ResponseEntity<?> compile(@RequestBody DeliveryRequest req, Authentication auth) {
        int day = req.day() == null ? 16 : req.day();
        Purpose purpose = req.purpose() == null ? Purpose.GLOBAL_ANALYSIS : req.purpose();
        String route = req.route() == null ? "MATERIALIZED" : req.route();
        Delivery d = deliveries.compile(day, purpose, route, actor(auth));
        return ResponseEntity.ok(d);
    }

    // ---------------- revocation ----------------

    public record RevokeRequest(Long consentEntryId) {
    }

    @PostMapping("/revocations/propagate")
    public ResponseEntity<?> propagate(@RequestBody RevokeRequest req, Authentication auth) {
        if (req.consentEntryId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "consentEntryId is required"));
        }
        return ResponseEntity.ok(revocation.propagateWithdrawal(req.consentEntryId()));
    }

    // ---------------- recovery + quarantine ----------------

    public record RecoveryRequest(String site) {
    }

    @PostMapping("/recovery/run")
    public RecoveryCheck runRecovery(@RequestBody RecoveryRequest req, Authentication auth) {
        return recovery.run(req.site(), actor(auth));
    }

    public record ResolveRequest(String note) {
    }

    @PostMapping("/quarantine/{code}/resolve")
    public QuarantineItem resolve(@PathVariable String code, @RequestBody(required = false) ResolveRequest req,
                                  Authentication auth) {
        return quarantine.resolve(code, actor(auth), req == null ? "resolved" : req.note());
    }

    // ---------------- rules registration ----------------

    public record RuleRequest(String code, DispositionScope scope, String siteCode, Purpose purpose,
                              RuleAction action, Integer retainYears, Integer destroyAfterDays,
                              String legalBasis) {
    }

    @PostMapping("/rules/disposition")
    public ResponseEntity<?> addRule(@RequestBody RuleRequest req, Authentication auth) {
        if (req.action() == null || req.legalBasis() == null || req.legalBasis().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "action and legalBasis are mandatory"));
        }
        DispositionRule r = new DispositionRule();
        r.setCode(req.code() == null || req.code().isBlank()
                ? "DR-" + System.currentTimeMillis() : req.code());
        r.setScope(req.scope() == null ? DispositionScope.STUDY : req.scope());
        r.setSiteCode(req.siteCode());
        r.setPurpose(req.purpose());
        r.setAction(req.action());
        r.setRetainYears(req.retainYears());
        r.setDestroyAfterDays(req.destroyAfterDays());
        r.setLegalBasis(req.legalBasis());
        r.setRegisteredAt(Instant.now());
        r.setRegisteredBy(actor(auth));
        r = ruleRepo.save(r);
        audit.record(actor(auth), "DISPOSITION_RULE_REGISTERED", "DispositionRule", r.getCode(),
                r.getAction() + " basis=" + r.getLegalBasis());
        return ResponseEntity.ok(r);
    }

    public record TieBreakRequest(String code, String precedence, String rationale) {
    }

    @PostMapping("/rules/tiebreak")
    public TieBreakRule addTieBreak(@RequestBody TieBreakRequest req, Authentication auth) {
        TieBreakRule t = new TieBreakRule();
        t.setCode(req.code() == null ? "TB-" + System.currentTimeMillis() : req.code());
        t.setPrecedence(req.precedence());
        t.setRationale(req.rationale());
        t.setRegisteredAt(Instant.now());
        t.setRegisteredBy(actor(auth));
        t = tieBreakRepo.save(t);
        audit.record(actor(auth), "TIEBREAK_RULE_REGISTERED", "TieBreakRule", t.getCode(), t.getPrecedence());
        return t;
    }
}
