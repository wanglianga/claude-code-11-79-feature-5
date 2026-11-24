package com.ctrial.service;

import com.ctrial.common.Text;
import com.ctrial.model.*;
import com.ctrial.model.Enums.*;
import com.ctrial.repo.*;
import com.ctrial.service.EligibilityEngine.Facts;
import com.ctrial.service.dto.EvalResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Gathers the facts valid at an analysis instant and feeds them to the shared
 * rule engine. This class is purpose-built for <b>replay</b>: nothing here
 * reads a pre-computed eligibility table. The materialised route computes the
 * same rows once and stores them; both go through {@link EligibilityEngine}.
 */
@Service
public class EligibilityService {

    private final StudyRepo studyRepo;
    private final SubjectRepo subjectRepo;
    private final ConsentRepo consentRepo;
    private final ProtocolRepo protocolRepo;
    private final EventRepo eventRepo;
    private final WatermarkRepo watermarkRepo;
    private final ApprovalRepo approvalRepo;
    private final DispositionRuleRepo ruleRepo;
    private final StudyClock clock;

    /** The cross-border analysis plane's physical destination; consent must cover it. */
    public static final String GLOBAL_DESTINATION = "EU";

    public EligibilityService(StudyRepo studyRepo, SubjectRepo subjectRepo, ConsentRepo consentRepo,
                              ProtocolRepo protocolRepo, EventRepo eventRepo, WatermarkRepo watermarkRepo,
                              ApprovalRepo approvalRepo, DispositionRuleRepo ruleRepo, StudyClock clock) {
        this.studyRepo = studyRepo;
        this.subjectRepo = subjectRepo;
        this.consentRepo = consentRepo;
        this.protocolRepo = protocolRepo;
        this.eventRepo = eventRepo;
        this.watermarkRepo = watermarkRepo;
        this.approvalRepo = approvalRepo;
        this.ruleRepo = ruleRepo;
        this.clock = clock;
    }

    public Study study() {
        return studyRepo.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Study not seeded"));
    }

    /** An immutable view of every ledger at one instant. Shared by both routes. */
    public record World(
            Study study,
            Instant analysisAt,
            int analysisDay,
            List<LocalEvent> events,
            Map<Long, List<ConsentEntry>> consentBySubject,
            List<ProtocolVersion> protocols,
            Map<String, Watermark> watermarks,
            List<DataUseApproval> approvals,
            Set<Long> supersededEventIds,
            boolean retentionRulePresent
    ) {
    }

    /**
     * Load the world at an instant. Consent entries are included when their
     * <b>local effective instant</b> is at or before the analysis instant; the
     * platform receipt time is irrelevant for validity. Protocol versions are
     * included for state assessment; the engine decides whether each one was
     * approved and effective at the occurrence.
     */
    @Transactional(readOnly = true)
    public World loadWorld(Instant analysisAt) {
        Study study = study();
        int day = clock.dayOf(study, analysisAt);
        List<LocalEvent> events = eventRepo.findAll().stream()
                .filter(e -> e.getOccurredAt().isBefore(analysisAt))
                .sorted(Comparator.comparing(LocalEvent::getOccurredAt).thenComparing(LocalEvent::getId))
                .toList();

        // revision chains: a row is superseded if a higher revision of the same stable id is in scope
        Map<String, Integer> maxRevision = new HashMap<>();
        for (LocalEvent e : events) {
            maxRevision.merge(e.getStableId(), e.getRevision(), Math::max);
        }
        Set<Long> superseded = events.stream()
                .filter(e -> maxRevision.getOrDefault(e.getStableId(), e.getRevision()) > e.getRevision())
                .map(LocalEvent::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<Long, List<ConsentEntry>> consent = new HashMap<>();
        for (Subject s : subjectRepo.findAll()) {
            consent.put(s.getId(), consentRepo
                    .findBySubjectIdAndEffectiveFromLessThanOrderByEffectiveFromAscRecordedAtAsc(s.getId(),
                            analysisAt.plusNanos(1000)));
        }

        Map<String, Watermark> wm = new HashMap<>();
        for (Watermark w : watermarkRepo.findAll()) {
            wm.put(w.getSiteCode() + "|" + w.getDomain(), w);
        }

        boolean retention = !ruleRepo.findByActiveTrueOrderByCodeAsc().isEmpty();
        return new World(study, analysisAt, day, events, consent, protocolRepo.findAll(),
                wm, approvalRepo.findByActiveTrueOrderByApprovedAtAsc(), superseded, retention);
    }

    /** Pick the envelope that governs a plane: global requires cross-border; local requires the site region. */
    public DataUseApproval approvalFor(World w, PlaneType plane, Purpose purpose) {
        return w.approvals().stream()
                .filter(a -> a.getPurpose() == purpose)
                .filter(a -> plane == PlaneType.GLOBAL ? a.isCrossBorder() : !a.isCrossBorder())
                .filter(DataUseApproval::isActive)
                .max(Comparator.comparing(DataUseApproval::getApprovedAt))
                .orElse(null);
    }

    public EvalResult evaluateOne(World w, LocalEvent e, PlaneType plane, Purpose purpose, String destinationRegion) {
        DataUseApproval ap = approvalFor(w, plane, purpose);
        Subject s = e.getSubject();
        String site = s.getSite().getCode();
        Watermark wm = w.watermarks().get(site + "|" + e.getDomain());
        Facts f = new Facts(e, site, s.getRegion(), s.getSite().isChinaResident(),
                destinationRegion, purpose, plane, w.analysisAt(), ap, wm,
                w.consentBySubject().getOrDefault(s.getId(), List.of()),
                w.protocols(), w.supersededEventIds().contains(e.getId()),
                w.retentionRulePresent());
        return EligibilityEngine.evaluate(f);
    }

    /**
     * Rows for the current world. For each candidate record we evaluate both
     * planes, because "local available" and "global available" are independent
     * outcomes of different envelopes — the UI and delivery each pick theirs.
     */
    public record RowDraft(LocalEvent event, Subject subject, PlaneType plane, Purpose purpose,
                           EvalResult result, int localDay) {
    }

    public List<RowDraft> evaluateWorld(World w, boolean includeLocalPlane) {
        List<RowDraft> out = new ArrayList<>();
        for (LocalEvent e : w.events()) {
            if (w.supersededEventIds().contains(e.getId())) {
                continue;
            }
            EvalResult global = evaluateOne(w, e, PlaneType.GLOBAL, Purpose.GLOBAL_ANALYSIS, GLOBAL_DESTINATION);
            out.add(new RowDraft(e, e.getSubject(), PlaneType.GLOBAL, Purpose.GLOBAL_ANALYSIS, global,
                    clock.dayOf(w.study(), e.getOccurredAt())));
            if (includeLocalPlane) {
                Purpose localPurpose = localPurposeFor(e.getSubject());
                EvalResult local = evaluateOne(w, e, PlaneType.LOCAL, localPurpose, e.getSubject().getRegion());
                out.add(new RowDraft(e, e.getSubject(), PlaneType.LOCAL, localPurpose, local,
                        clock.dayOf(w.study(), e.getOccurredAt())));
            }
        }
        out.sort(Comparator.comparing((RowDraft r) -> r.event().getOccurredAt())
                .thenComparing(r -> r.subject().getPseudonym())
                .thenComparing(r -> r.plane()));
        return out;
    }

    private Purpose localPurposeFor(Subject subject) {
        return subject.getSite().isChinaResident() ? Purpose.CHINA_LOCAL_ANALYSIS : Purpose.LOCAL_ANALYSIS;
    }

    // ---------------- watermark views ----------------

    public record CoverageRow(String siteCode, String domain,
                              long sourceConfirmed, long transferAck, long analysis,
                              long pendingTransfer, long pendingAnalysis,
                              Instant sourceConfirmedAt, Instant analysisAt,
                              Boolean linkDown, List<String> unconfirmedDays) {
    }

    /**
     * Per-site/domain coverage with an explicit list of study days that are
     * <b>unconfirmed</b> (above the analysis watermark), so the UI can render a
     * gap band instead of a zero.
     */
    public List<CoverageRow> coverage(int throughDay) {
        Study study = study();
        Instant analysisAt = clock.analysisInstant(study, throughDay);
        List<CoverageRow> out = new ArrayList<>();
        for (Watermark w : watermarkRepo.findAllByOrderBySiteCodeAscDomainAsc()) {
            List<String> days = new ArrayList<>();
            List<LocalEvent> events = eventRepo.findAll().stream()
                    .filter(e -> e.getDomain() == w.getDomain())
                    .filter(e -> e.getSubject().getSite().getCode().equals(w.getSiteCode()))
                    .filter(e -> e.getSourceSeq() > w.getAnalysisSeq())
                    .filter(e -> e.getOccurredAt().isBefore(analysisAt))
                    .toList();
            Set<String> unique = new TreeSet<>();
            for (LocalEvent e : events) {
                unique.add("D" + clock.dayOf(study, e.getOccurredAt()));
            }
            days.addAll(unique);
            int firstUnconfirmed = events.stream().mapToInt(e -> clock.dayOf(study, e.getOccurredAt())).min().orElse(-1);
            if (firstUnconfirmed > 0) {
                for (int d = firstUnconfirmed; d <= throughDay; d++) {
                    String label = "D" + d;
                    if (!days.contains(label)) {
                        days.add(label);
                    }
                }
            }
            out.add(new CoverageRow(w.getSiteCode(), w.getDomain().name(),
                    w.getSourceConfirmedSeq(), w.getTransferAckSeq(), w.getAnalysisSeq(),
                    w.pendingTransfer(), w.pendingAnalysis(), w.getSourceConfirmedAt(), w.getAnalysisAt(),
                    w.linkDown(), days));
        }
        return out;
    }

    /** Canonical watermark pin string used by the delivery compiler. */
    public String pinWatermarks(int throughDay) {
        Study study = study();
        Instant analysisAt = clock.analysisInstant(study, throughDay);
        List<Watermark> all = watermarkRepo.findAllByOrderBySiteCodeAscDomainAsc();
        return all.stream()
                .map(w -> w.getSiteCode() + ":" + w.getDomain() + "=" + w.getAnalysisSeq()
                        + "(src=" + w.getSourceConfirmedSeq() + ",ack=" + w.getTransferAckSeq() + ")")
                .collect(Collectors.joining("; "));
    }

    public String watermarkDigest(int throughDay) {
        return Text.sha256(pinWatermarks(throughDay));
    }
}
