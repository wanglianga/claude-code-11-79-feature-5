package com.ctrial.service;

import com.ctrial.common.Text;
import com.ctrial.model.*;
import com.ctrial.model.Enums.*;
import com.ctrial.repo.*;
import com.ctrial.service.EligibilityService.RowDraft;
import com.ctrial.service.EligibilityService.World;
import com.ctrial.service.dto.EvalResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Two reconstruction routes over the same rule engine.
 *
 * <ul>
 *   <li><b>MATERIALIZED</b> — evaluate once, persist one row per candidate, then
 *       answer queries directly from those rows. Later consent/protocol
 *       revisions mark affected runs STALE (invalidation propagation), so a
 *       stale run can never authorise a new read.</li>
 *   <li><b>REPLAY</b> — rebuild on demand from the append-only consent, protocol
 *       and event ledgers at a chosen instant; nothing is persisted. Requires a
 *       completeness proof over the watermarks; the cost is per query.</li>
 * </ul>
 * Both call {@link EligibilityService#loadWorld} + {@link EligibilityEngine}, and
 * the equivalence test asserts identical legal sets, verdicts and reason codes.
 */
@Service
public class EligibilityRunService {

    private final EligibilityService eligibility;
    private final StudyClock clock;
    private final EligibilityRunRepo runRepo;
    private final EligibilityRowRepo rowRepo;
    private final DeliveryRepo deliveryRepo;
    private final DeliveryRecordRepo deliveryRecordRepo;
    private final AuditService audit;

    public EligibilityRunService(EligibilityService eligibility, StudyClock clock, EligibilityRunRepo runRepo,
                                 EligibilityRowRepo rowRepo, DeliveryRepo deliveryRepo,
                                 DeliveryRecordRepo deliveryRecordRepo, AuditService audit) {
        this.eligibility = eligibility;
        this.clock = clock;
        this.runRepo = runRepo;
        this.rowRepo = rowRepo;
        this.deliveryRepo = deliveryRepo;
        this.deliveryRecordRepo = deliveryRecordRepo;
        this.audit = audit;
    }

    // ---------------- materialised route ----------------

    @Transactional
    public EligibilityRun materialize(int day, String actor) {
        World w = eligibility.loadWorld(clock.analysisInstant(eligibility.study(), day));
        List<RowDraft> drafts = eligibility.evaluateWorld(w, true);
        String code = nextRunCode(day, "MAT");
        EligibilityRun run = new EligibilityRun();
        run.setCode(code);
        run.setAnalysisAt(w.analysisAt());
        run.setAnalysisDay(day);
        run.setRoute("MATERIALIZED");
        run.setStatus(SnapshotStatus.VALID);
        run.setApproval(eligibility.approvalFor(w, PlaneType.GLOBAL, Purpose.GLOBAL_ANALYSIS));
        run.setWatermarks(eligibility.pinWatermarks(day));
        run.setCompletenessOk(noGaps(w, drafts));
        run.setCreatedAt(Instant.now());
        run.setCreatedBy(actor);

        List<EligibilityRow> rows = new ArrayList<>();
        for (RowDraft d : drafts) {
            EligibilityRow r = new EligibilityRow();
            r.setEvent(d.event());
            r.setSubjectPseudonym(d.subject().getPseudonym());
            r.setSiteCode(d.subject().getSite().getCode());
            r.setDomain(d.event().getDomain().name());
            r.setStableId(d.event().getStableId());
            r.setRevision(d.event().getRevision());
            r.setOccurredAt(d.event().getOccurredAt());
            r.setLocalDay(d.localDay());
            r.setEligibility(d.result().eligibility());
            r.setVerdict(d.result().verdict());
            r.setPlane(d.plane());
            r.setReasonCodes(d.result().reasonCodeCsv());
            r.setReasons(d.result().reasonText());
            r.setProtocolVersion(d.result().protocolVersion() == null ? "" : d.result().protocolVersion());
            r.setProjectedFields(d.result().projectedCsv());
            r.setWatermarkGap(d.result().watermarkGap());
            r.setRowHash(Text.sha256(run.getCode() + "|" + r.getSubjectPseudonym() + "|" + r.getStableId()
                    + "#" + r.getRevision() + "|" + d.plane() + "|" + d.result().canonical()));
            rows.add(r);
        }
        run.setRowCount(rows.size());
        run.setGlobalCount((int) rows.stream().filter(r -> r.getPlane() == PlaneType.GLOBAL).count());
        run.setLocalCount((int) rows.stream().filter(r -> r.getPlane() == PlaneType.LOCAL).count());
        run.setPendingCount((int) rows.stream().filter(r -> r.getEligibility() == Eligibility.PENDING).count());
        run.setSetDigest(Text.sha256(rows.stream().map(EligibilityRow::getRowHash).sorted()
                .collect(Collectors.joining("|"))));
        run = runRepo.save(run);
        for (EligibilityRow r : rows) {
            r.setRun(run);
        }
        rowRepo.saveAll(rows);
        audit.record(actor, "ELIGIBILITY_MATERIALIZED", "EligibilityRun", code,
                "Materialised " + rows.size() + " rows at analysis day " + day
                        + (run.isCompletenessOk() ? "; watermarks complete" : "; watermarks show UNCONFIRMED gaps"));
        return run;
    }

    // ---------------- replay route ----------------

    /** A transient recomputation; not persisted and therefore not reusable as an authorisation. */
    public ReplayResult replay(int day) {
        World w = eligibility.loadWorld(clock.analysisInstant(eligibility.study(), day));
        List<RowDraft> drafts = eligibility.evaluateWorld(w, true);
        List<ReplayRow> rows = drafts.stream().map(d -> {
            EvalResult r = d.result();
            return new ReplayRow(d.subject().getPseudonym(), d.subject().getSite().getCode(),
                    d.event().getDomain().name(), d.event().getStableId(), d.event().getRevision(),
                    d.localDay(), d.plane(), r.eligibility(), r.verdict(), r.reasonCodeCsv(),
                    r.reasonText(), r.protocolVersion(), r.projectedCsv(), r.watermarkGap());
        }).toList();
        String digest = Text.sha256(rows.stream().map(ReplayRow::canonical).sorted()
                .collect(Collectors.joining("|")));
        boolean complete = noGaps(w, drafts);
        return new ReplayResult(day, w.analysisAt(), rows, digest, complete,
                eligibility.pinWatermarks(day), completenessNotes(w, drafts));
    }

    public record ReplayRow(String pseudonym, String siteCode, String domain, String stableId, int revision,
                            int localDay, PlaneType plane, Eligibility eligibility, Verdict verdict,
                            String reasonCodes, String reasons, String protocolVersion,
                            String projectedFields, boolean watermarkGap) {
        public String canonical() {
            return pseudonym + "|" + stableId + "#" + revision + "|" + plane + "|" + eligibility + "|"
                    + verdict + "|" + reasonCodes + "|" + protocolVersion + "|" + projectedFields;
        }
    }

    public record ReplayResult(int analysisDay, Instant analysisAt, List<ReplayRow> rows, String setDigest,
                               boolean completenessOk, String watermarks, List<String> completenessNotes) {
    }

    @Transactional(readOnly = true)
    public Optional<EligibilityRun> findRun(String code) {
        return runRepo.findByCode(code);
    }

    @Transactional(readOnly = true)
    public List<EligibilityRow> rows(String code) {
        return runRepo.findByCode(code).map(r -> rowRepo.findByRunIdOrderByLocalDayAscSubjectPseudonymAsc(r.getId()))
                .orElse(List.of());
    }

    @Transactional(readOnly = true)
    public List<EligibilityRow> rows(String code, PlaneType plane) {
        return runRepo.findByCode(code).map(r -> rowRepo.findByRunIdAndPlaneOrderByOccurredAtAsc(r.getId(), plane))
                .orElse(List.of());
    }

    /**
     * Side-effect-free equivalence check: materialise the row set in memory from
     * one load of the world, replay it from a second independent load, and
     * compare the canonical keys. Used by read-only screens; the persisted
     * variant (which writes a run) is exercised by the test suite and by the
     * explicit "compare" endpoint.
     */
    public Equivalence equivalenceReadOnly(int day) {
        World w1 = eligibility.loadWorld(clock.analysisInstant(eligibility.study(), day));
        World w2 = eligibility.loadWorld(clock.analysisInstant(eligibility.study(), day));
        Map<String, String> mat = new LinkedHashMap<>();
        for (RowDraft d : eligibility.evaluateWorld(w1, true)) {
            mat.put(key(d.subject().getPseudonym(), d.event().getStableId(), d.event().getRevision(), d.plane()),
                    d.result().canonical());
        }
        Map<String, String> rep = new LinkedHashMap<>();
        List<RowDraft> drafts2 = eligibility.evaluateWorld(w2, true);
        for (RowDraft d : drafts2) {
            rep.put(key(d.subject().getPseudonym(), d.event().getStableId(), d.event().getRevision(), d.plane()),
                    d.result().canonical());
        }
        List<String> diffs = new ArrayList<>();
        for (String k : union(mat.keySet(), rep.keySet())) {
            if (!Objects.equals(mat.get(k), rep.get(k))) {
                diffs.add(k + " :: " + mat.get(k) + " vs " + rep.get(k));
            }
        }
        Set<String> matLegal = mat.entrySet().stream()
                .filter(e -> e.getValue().startsWith("GLOBAL_AVAILABLE") || e.getValue().startsWith("LOCAL_AVAILABLE"))
                .map(Map.Entry::getKey).collect(Collectors.toCollection(TreeSet::new));
        Set<String> repLegal = rep.entrySet().stream()
                .filter(e -> e.getValue().startsWith("GLOBAL_AVAILABLE") || e.getValue().startsWith("LOCAL_AVAILABLE"))
                .map(Map.Entry::getKey).collect(Collectors.toCollection(TreeSet::new));
        boolean complete = drafts2.stream().noneMatch(d -> d.result().watermarkGap());
        return new Equivalence(day, "(read-only)", mat.size(), matLegal.size(), repLegal.size(),
                diffs.isEmpty(), diffs, matLegal, repLegal, complete, complete,
                Text.sha256(String.join("|", mat.values())), Text.sha256(String.join("|", rep.values())));
    }

    // ---------------- equivalence ----------------

    /**
     * Compare both routes at the same instant. Equality is required for the
     * legal membership plus verdict and reason codes; row order is irrelevant.
     * This is the programmatic proof that the two reconstruction strategies
     * encode the same rules.
     */
    @Transactional
    public Equivalence compare(int day) {
        EligibilityRun mat = materialize(day, "system:equivalence");
        List<EligibilityRow> matRows = rowRepo.findByRunIdOrderByLocalDayAscSubjectPseudonymAsc(mat.getId());
        ReplayResult rep = replay(day);

        Set<String> matSet = matRows.stream()
                .filter(r -> r.getEligibility() == Eligibility.LOCAL_AVAILABLE || r.getEligibility() == Eligibility.GLOBAL_AVAILABLE)
                .map(EligibilityRow::getRowHash).collect(Collectors.toCollection(TreeSet::new));
        // the canonical key is route-independent, so recompute from the row's rule fields
        Map<String, String> matCanon = new LinkedHashMap<>();
        for (EligibilityRow r : matRows) {
            matCanon.put(key(r.getSubjectPseudonym(), r.getStableId(), r.getRevision(), r.getPlane()),
                    r.getEligibility() + "|" + r.getVerdict() + "|" + r.getReasonCodes() + "|"
                            + r.getProtocolVersion() + "|" + r.getProjectedFields());
        }
        Map<String, String> repCanon = new LinkedHashMap<>();
        for (ReplayRow r : rep.rows()) {
            repCanon.put(key(r.pseudonym(), r.stableId(), r.revision(), r.plane()),
                    r.eligibility() + "|" + r.verdict() + "|" + r.reasonCodes() + "|"
                            + (r.protocolVersion() == null ? "" : r.protocolVersion()) + "|" + r.projectedFields());
        }
        List<String> diffs = new ArrayList<>();
        for (String k : union(matCanon.keySet(), repCanon.keySet())) {
            String a = matCanon.get(k);
            String b = repCanon.get(k);
            if (!Objects.equals(a, b)) {
                diffs.add(k + " :: materialized=" + a + " :: replay=" + b);
            }
        }
        Set<String> matLegal = matCanon.entrySet().stream()
                .filter(e -> e.getValue().startsWith("GLOBAL_AVAILABLE") || e.getValue().startsWith("LOCAL_AVAILABLE"))
                .map(Map.Entry::getKey).collect(Collectors.toCollection(TreeSet::new));
        Set<String> repLegal = repCanon.entrySet().stream()
                .filter(e -> e.getValue().startsWith("GLOBAL_AVAILABLE") || e.getValue().startsWith("LOCAL_AVAILABLE"))
                .map(Map.Entry::getKey).collect(Collectors.toCollection(TreeSet::new));
        return new Equivalence(day, mat.getCode(), matSet.size(), matLegal.size(), repLegal.size(),
                diffs.isEmpty(), diffs, matLegal, repLegal, mat.isCompletenessOk(), rep.completenessOk(),
                mat.getSetDigest(), rep.setDigest());
    }

    public record Equivalence(int analysisDay, String materializedRun, int materializedRows,
                              int materializedLegal, int replayLegal, boolean equal,
                              List<String> differences, Set<String> materializedLegalKeys,
                              Set<String> replayLegalKeys, boolean materializedComplete,
                              boolean replayComplete, String materializedDigest, String replayDigest) {
    }

    private static String key(String pseudonym, String stableId, int revision, PlaneType plane) {
        return pseudonym + "|" + stableId + "#" + revision + "|" + plane;
    }

    private static <T> Set<T> union(Set<T> a, Set<T> b) {
        Set<T> out = new TreeSet<>(Comparator.comparing(Object::toString));
        out.addAll(a);
        out.addAll(b);
        return out;
    }

    // ---------------- invalidation ----------------

    /**
     * Mark VALID runs STALE when a change landed after they were compiled and
     * could affect them. A stale run must never be used to authorise a new read
     * or a new delivery; it is kept for audit and can be recomputed.
     */
    @Transactional
    public int invalidateRunsAffectedBy(String trigger, Instant effectiveFrom, String detail) {
        return invalidateRuns(trigger, effectiveFrom, detail, null);
    }

    /**
     * Mark snapshots STALE when a change could alter what they previously
     * authorised. With a {@code pseudonym}, only runs that actually carried one
     * of that subject's <em>eligible</em> rows are invalidated — a run that
     * already blocked the subject does not become stale, because it never
     * authorised the data the change now forbids.
     */
    @Transactional
    public int invalidateRuns(String trigger, Instant effectiveFrom, String detail, String pseudonym) {
        int affected = 0;
        for (EligibilityRun run : runRepo.findAll()) {
            if (run.getStatus() != SnapshotStatus.VALID) {
                continue;
            }
            if (effectiveFrom.isAfter(run.getAnalysisAt())) {
                continue;
            }
            if (pseudonym != null) {
                boolean authorised = rowRepo.findByRunIdAndSubjectPseudonym(run.getId(), pseudonym).stream()
                        .anyMatch(r -> r.getEligibility() == Eligibility.LOCAL_AVAILABLE
                                || r.getEligibility() == Eligibility.GLOBAL_AVAILABLE);
                if (!authorised) {
                    continue;
                }
            }
            run.setStatus(SnapshotStatus.STALE);
            run.setStaleReason(trigger + " effective " + effectiveFrom + ": " + detail);
            runRepo.save(run);
            affected++;
        }
        if (affected > 0) {
            audit.record("system:propagation", "RUNS_INVALIDATED", "EligibilityRun", "batch",
                    affected + " run(s) marked STALE by " + trigger + " effective " + effectiveFrom + " — " + detail);
        }
        return affected;
    }

    // ---------------- helpers ----------------

    private boolean noGaps(World w, List<RowDraft> drafts) {
        return drafts.stream().noneMatch(d -> d.result().watermarkGap());
    }

    private List<String> completenessNotes(World w, List<RowDraft> drafts) {
        List<String> notes = new ArrayList<>();
        for (RowDraft d : drafts) {
            if (d.result().watermarkGap()) {
                String n = d.subject().getSite().getCode() + "/" + d.event().getDomain()
                        + " seq " + d.event().getSourceSeq() + " " + d.result().reasonCodeCsv();
                if (!notes.contains(n)) {
                    notes.add(n);
                }
            }
        }
        return notes;
    }

    private String nextRunCode(int day, String prefix) {
        String base = prefix + "-D" + day;
        long n = runRepo.findAll().stream().filter(r -> r.getCode().startsWith(base)).count();
        return base + "-" + String.format("%03d", n + 1);
    }
}
