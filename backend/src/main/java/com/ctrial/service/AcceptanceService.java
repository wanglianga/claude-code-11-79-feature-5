package com.ctrial.service;

import com.ctrial.model.*;
import com.ctrial.model.Enums.*;
import com.ctrial.repo.*;
import com.ctrial.service.EligibilityService.RowDraft;
import com.ctrial.service.EligibilityService.World;
import com.ctrial.service.dto.EvalResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Encodes the four acceptance subjects as executable checks at a chosen
 * analysis day. Each check states what the spec requires and returns the
 * observed evaluation, so the UI can show a pass/fail card rather than a raw
 * table. Nothing here is special-cased: every verdict comes from the same
 * engine the deliveries use.
 */
@Service
public class AcceptanceService {

    private final EligibilityService eligibility;
    private final StudyClock clock;
    private final SubjectRepo subjectRepo;
    private final ConsentRepo consentRepo;
    private final DeliveryRepo deliveryRepo;
    private final DeliveryRecordRepo recordRepo;
    private final EligibilityRunService runs;

    public AcceptanceService(EligibilityService eligibility, StudyClock clock, SubjectRepo subjectRepo,
                             ConsentRepo consentRepo, DeliveryRepo deliveryRepo,
                             DeliveryRecordRepo recordRepo, EligibilityRunService runs) {
        this.eligibility = eligibility;
        this.clock = clock;
        this.subjectRepo = subjectRepo;
        this.consentRepo = consentRepo;
        this.deliveryRepo = deliveryRepo;
        this.recordRepo = recordRepo;
        this.runs = runs;
    }

    public record Check(String subject, String code, String requirement, String expected, String observed,
                        boolean pass) {
    }

    public record AcceptanceReport(int analysisDay, List<Check> checks, boolean allPass, String summary,
                                   List<CoverageNote> coverage, List<String> extraNotes) {
    }

    public record CoverageNote(String site, String domain, long sourceConfirmed, long transferAck, long analysis,
                               List<String> unconfirmedDays) {
    }

    @Transactional(readOnly = true)
    public AcceptanceReport report(int day) {
        World w = eligibility.loadWorld(clock.analysisInstant(eligibility.study(), day));
        Map<String, Subject> byPseudo = new HashMap<>();
        for (Subject s : subjectRepo.findAll()) {
            byPseudo.put(s.getPseudonym(), s);
        }
        List<RowDraft> drafts = eligibility.evaluateWorld(w, true);
        Map<String, EvalResult> global = new HashMap<>();
        Map<String, EvalResult> local = new HashMap<>();
        for (RowDraft d : drafts) {
            String key = d.subject().getPseudonym() + "|" + d.event().getStableId();
            if (d.plane() == PlaneType.GLOBAL) {
                global.put(key, d.result());
            } else {
                local.put(key, d.result());
            }
        }
        List<Check> checks = new ArrayList<>();

        // ---- A: China-only consent, day-11 visit uploaded day 15 ----
        EvalResult aVisitGlobal = global.get("PSN-A1|VST-A1-V1");
        EvalResult aVisitLocal = local.get("PSN-A1|VST-A1-V1");
        checks.add(new Check("A", "A1", "A's day-11 visit must not leave China (no global purpose was granted).",
                "GLOBAL plane ≠ available",
                aVisitGlobal == null ? "missing" : aVisitGlobal.eligibility() + " [" + aVisitGlobal.reasonCodeCsv() + "]",
                aVisitGlobal != null && aVisitGlobal.eligibility() != Eligibility.GLOBAL_AVAILABLE));
        checks.add(new Check("A", "A2", "The same visit stays locally available at the China site.",
                "LOCAL plane = LOCAL_AVAILABLE",
                aVisitLocal == null ? "missing" : aVisitLocal.eligibility() + " [" + aVisitLocal.reasonCodeCsv() + "]",
                aVisitLocal != null && aVisitLocal.eligibility() == Eligibility.LOCAL_AVAILABLE));
        checks.add(new Check("A", "A3", "The reason for exclusion is the consent region/purpose, not the upload delay.",
                "reason cites consent scope",
                aVisitGlobal == null ? "missing" : aVisitGlobal.reasonCodeCsv(),
                aVisitGlobal != null && (aVisitGlobal.reasonCodeCsv().contains("CONSENT_REGION_EXCLUDED")
                        || aVisitGlobal.reasonCodeCsv().contains("CONSENT_NO_GRANT_FOR_PURPOSE"))
                        && !aVisitGlobal.reasonCodeCsv().contains("WATERMARK_UNCONFIRMED")));

        // ---- B: withdrawal on D12; AE on D11; visit on D13 ----
        EvalResult bAeGlobal = global.get("PSN-B2|AE-B2-01");
        EvalResult bAeLocal = local.get("PSN-B2|AE-B2-01");
        EvalResult bVisitGlobal = global.get("PSN-B2|VST-B2-V2");
        EvalResult bVisitLocal = local.get("PSN-B2|VST-B2-V2");
        checks.add(new Check("B", "B1", "The day-11 AE must not enter a new global query after the day-12 withdrawal.",
                "GLOBAL plane = BLOCKED, reason cites held-at-occurrence",
                bAeGlobal == null ? "missing" : bAeGlobal.eligibility() + " [" + bAeGlobal.reasonCodeCsv() + "]",
                bAeGlobal != null && bAeGlobal.eligibility() == Eligibility.BLOCKED
                        && bAeGlobal.reasonCodeCsv().contains("CONSENT_HELD_AT_OCCURRENCE")));
        checks.add(new Check("B", "B2", "The day-11 AE may still be retained under the pre-registered restricted basis.",
                "reason includes RETENTION_RESTRICTED_BASIS",
                bAeGlobal == null ? "missing" : bAeGlobal.reasonCodeCsv(),
                bAeGlobal != null && bAeGlobal.reasonCodeCsv().contains("RETENTION_RESTRICTED_BASIS")));
        checks.add(new Check("B", "B3", "The day-13 visit (after withdrawal) has no basis at all and is handled separately.",
                "GLOBAL = BLOCKED with CONSENT_WITHDRAWN_BEFORE_OCCURRENCE",
                bVisitGlobal == null ? "missing" : bVisitGlobal.eligibility() + " [" + bVisitGlobal.reasonCodeCsv() + "]",
                bVisitGlobal != null && bVisitGlobal.eligibility() == Eligibility.BLOCKED
                        && bVisitGlobal.reasonCodeCsv().contains("CONSENT_WITHDRAWN_BEFORE_OCCURRENCE")));
        checks.add(new Check("B", "B4", "Local use continues for the SG site after the global withdrawal.",
                "LOCAL plane available for both B rows",
                (bAeLocal == null ? "missing" : bAeLocal.eligibility().name()) + " / "
                        + (bVisitLocal == null ? "missing" : bVisitLocal.eligibility().name()),
                bAeLocal != null && bAeLocal.eligibility() == Eligibility.LOCAL_AVAILABLE
                        && bVisitLocal != null && bVisitLocal.eligibility() == Eligibility.LOCAL_AVAILABLE));

        // ---- C: new protocol v2.0 effective D14 ----
        EvalResult cD13 = global.get("PSN-C3|IMG-C3-D13");
        EvalResult cD15 = global.get("PSN-C3|IMG-C3-D15");
        checks.add(new Check("C", "C1", "C's day-13 imaging is not evaluated under v2.0, which is not yet effective.",
                "protocol = v1.2",
                cD13 == null ? "missing" : cD13.protocolVersion(),
                cD13 != null && "v1.2".equals(cD13.protocolVersion())));
        checks.add(new Check("C", "C2", "C's day-15 imaging is evaluated under v2.0 and carries the new imaging fields.",
                "protocol = v2.0 and fields include IMAGING_ADC",
                cD15 == null ? "missing" : cD15.protocolVersion() + " [" + cD15.projectedCsv() + "]",
                cD15 != null && "v2.0".equals(cD15.protocolVersion())
                        && cD15.projectedCsv().contains("IMAGING_ADC")));
        // v2.0 applies to CN-01 only: the same field set at another site must not be treated as v2.0
        checks.add(new Check("C", "C3", "v2.0's site scope is respected: it applies to CN-01 only.",
                "no CN-02/SG row is evaluated under v2.0",
                otherSiteV2(w) ? "a non-CN-01 row was evaluated under v2.0" : "none",
                !otherSiteV2(w)));

        // ---- D: CN-02 stalled at day 9 ----
        EvalResult dD5 = global.get("PSN-D4|RAND-D4-01");
        EvalResult dD12 = global.get("PSN-D4|VST-D4-V1");
        EvalResult dD12Local = local.get("PSN-D4|VST-D4-V1");
        checks.add(new Check("D", "D1", "D's day-5 record, inside the confirmed range, is globally available.",
                "GLOBAL = GLOBAL_AVAILABLE",
                dD5 == null ? "missing" : dD5.eligibility() + " [" + dD5.reasonCodeCsv() + "]",
                dD5 != null && dD5.eligibility() == Eligibility.GLOBAL_AVAILABLE));
        checks.add(new Check("D", "D2", "D's day-12 record is UNCONFIRMED (pending), never counted as no-event.",
                "GLOBAL = PENDING with WATERMARK_UNCONFIRMED",
                dD12 == null ? "missing" : dD12.eligibility() + " [" + dD12.reasonCodeCsv() + "]",
                dD12 != null && dD12.eligibility() == Eligibility.PENDING
                        && dD12.reasonCodeCsv().contains("WATERMARK_UNCONFIRMED")));
        checks.add(new Check("D", "D3", "The same day-12 record remains locally available at its China site.",
                "LOCAL = LOCAL_AVAILABLE",
                dD12Local == null ? "missing" : dD12Local.eligibility() + " [" + dD12Local.reasonCodeCsv() + "]",
                dD12Local != null && dD12Local.eligibility() == Eligibility.LOCAL_AVAILABLE));

        // ---- delivery traceability ----
        boolean anyRevokedDelivery = false;
        boolean auditKept = true;
        String revokedNote = "no delivery contains subject B";
        for (Delivery d : deliveryRepo.findAllByOrderByCompiledAtDesc()) {
            List<DeliveryRecord> recs = recordRepo.findByDeliveryIdAndSubjectPseudonym(d.getId(), "PSN-B2");
            if (recs.isEmpty()) {
                continue;
            }
            anyRevokedDelivery = true;
            boolean contentBlocked = recs.stream().allMatch(DeliveryRecord::isAccessRevoked);
            boolean summaryIntact = d.getContentDigest() != null && d.getLegalMemberDigest() != null
                    && d.getLegalMemberCount() >= recs.size();
            revokedNote = d.getCode() + " status=" + d.getStatus() + " accessRevoked=" + contentBlocked
                    + " summaryIntact=" + summaryIntact;
            auditKept &= contentBlocked && summaryIntact;
        }
        checks.add(new Check("B", "B5",
                "If a historical delivery contains B's day-11 record, its access is revoked and its summary kept.",
                "either no such delivery, or content blocked AND summary/digests intact",
                revokedNote,
                !anyRevokedDelivery || auditKept));

        // ---- coverage band ----
        List<CoverageNote> coverage = new ArrayList<>();
        for (Watermark wm : w.watermarks().values().stream()
                .sorted(Comparator.comparing(Watermark::getSiteCode).thenComparing(x -> x.getDomain().name()))
                .toList()) {
            if (wm.getAnalysisSeq() >= wm.getSourceConfirmedSeq()) {
                continue;
            }
            List<String> days = new ArrayList<>();
            for (LocalEvent e : w.events()) {
                if (e.getDomain() != wm.getDomain()) {
                    continue;
                }
                if (!e.getSubject().getSite().getCode().equals(wm.getSiteCode())) {
                    continue;
                }
                if (e.getSourceSeq() <= wm.getAnalysisSeq()) {
                    continue;
                }
                days.add("D" + clock.dayOf(w.study(), e.getOccurredAt()));
            }
            if (!days.isEmpty()) {
                coverage.add(new CoverageNote(wm.getSiteCode(), wm.getDomain().name(),
                        wm.getSourceConfirmedSeq(), wm.getTransferAckSeq(), wm.getAnalysisSeq(), days));
            }
        }

        // ---- reconstruction equivalence ----
        EligibilityRunService.Equivalence eq = runs.equivalenceReadOnly(day);
        checks.add(new Check("ALL", "EQ", "Both reconstruction routes yield the same legal set at this instant.",
                "materialised set == replay set",
                "materializedLegal=" + eq.materializedLegal() + " replayLegal=" + eq.replayLegal()
                        + " diffs=" + eq.differences().size(),
                eq.equal() && eq.materializedLegal() == eq.replayLegal()));

        boolean allPass = checks.stream().allMatch(Check::pass);
        long passed = checks.stream().filter(Check::pass).count();
        String summary = passed + "/" + checks.size() + " acceptance checks pass at analysis day " + day + "."
                + (allPass ? " The dual-domain rules, watermarks and delivery traceability agree."
                : " See the failing checks for detail.");

        List<String> extras = new ArrayList<>();
        extras.add("Both routes use one engine; materialisation is a cache, not a second source of truth.");
        extras.add("A stale or audit-only snapshot is never consulted to authorise a new read or delivery.");
        extras.add("Gaps above the analysis watermark are reported as UNCONFIRMED, never as zero events.");
        return new AcceptanceReport(day, checks, allPass, summary, coverage, extras);
    }

    private boolean otherSiteV2(World w) {
        for (LocalEvent e : w.events()) {
            if (e.getSubject().getSite().getCode().equals("CN-01")) {
                continue;
            }
            EvalResult r = eligibility.evaluateOne(w, e, PlaneType.GLOBAL, Purpose.GLOBAL_ANALYSIS,
                    EligibilityService.GLOBAL_DESTINATION);
            if ("v2.0".equals(r.protocolVersion())) {
                return true;
            }
        }
        return false;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> consentTimeline(String pseudonym) {
        Subject s = subjectRepo.findByPseudonym(pseudonym)
                .orElseThrow(() -> new IllegalArgumentException("Unknown pseudonym " + pseudonym));
        List<Map<String, Object>> out = new ArrayList<>();
        for (ConsentEntry c : consentRepo.findBySubjectIdOrderByEffectiveFromAscRecordedAtAsc(s.getId())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("kind", c.getKind());
            m.put("purposes", c.getPurposes());
            m.put("fieldGroups", c.getFieldGroups());
            m.put("regions", c.getRegions());
            m.put("effectiveFrom", c.getEffectiveFrom());
            m.put("recordedAt", c.getRecordedAt());
            m.put("documentRef", c.getDocumentRef());
            m.put("note", c.getNote());
            out.add(m);
        }
        return out;
    }
}
