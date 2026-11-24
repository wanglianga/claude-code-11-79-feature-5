package com.ctrial.service;

import com.ctrial.common.Text;
import com.ctrial.model.*;
import com.ctrial.model.Enums.*;
import com.ctrial.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Snapshot delivery compiler. Pins the approved versions, legal member
 * summary, field projection, filter condition and per-domain source
 * watermarks used for one delivery, then writes a de-identified projection.
 *
 * <p>Authorisation rule enforced here: a delivery may only be compiled from a
 * VALID materialised run (or a fresh replay). A STALE or AUDIT_ONLY snapshot can
 * never be reused to authorise a new read, and an already-compiled delivery is
 * never re-used as consent for a later query.
 */
@Service
public class DeliveryService {

    private final EligibilityService eligibility;
    private final EligibilityRunService runs;
    private final StudyClock clock;
    private final DeliveryRepo deliveryRepo;
    private final DeliveryRecordRepo recordRepo;
    private final EligibilityRowRepo rowRepo;
    private final ApprovalRepo approvalRepo;
    private final AuditService audit;

    public DeliveryService(EligibilityService eligibility, EligibilityRunService runs, StudyClock clock,
                           DeliveryRepo deliveryRepo, DeliveryRecordRepo recordRepo,
                           EligibilityRowRepo rowRepo, ApprovalRepo approvalRepo, AuditService audit) {
        this.eligibility = eligibility;
        this.runs = runs;
        this.clock = clock;
        this.deliveryRepo = deliveryRepo;
        this.recordRepo = recordRepo;
        this.rowRepo = rowRepo;
        this.approvalRepo = approvalRepo;
        this.audit = audit;
    }

    /**
     * Compile a delivery at an analysis day using the given route. Both routes
     * must produce identical membership; the compiler asserts this before it
     * writes anything.
     */
    @Transactional
    public Delivery compile(int day, Purpose purpose, String route, String actor) {
        if (purpose != Purpose.GLOBAL_ANALYSIS) {
            throw new IllegalArgumentException("Deliveries are compiled for the cross-border purpose only; "
                    + purpose + " is a local-purpose evaluation.");
        }
        EligibilityService.World world = eligibility.loadWorld(clock.analysisInstant(eligibility.study(), day));
        DataUseApproval approval = eligibility.approvalFor(world, PlaneType.GLOBAL, purpose);
        if (approval == null) {
            throw new IllegalStateException("No active cross-border approval for purpose " + purpose
                    + "; a delivery cannot be compiled.");
        }

        List<EligibilityRow> rows;
        EligibilityRun materialized = runs.materialize(day, actor + (route.equalsIgnoreCase("REPLAY") ? ":replay-check" : ""));
        rows = rowRepo.findByRunIdOrderByLocalDayAscSubjectPseudonymAsc(materialized.getId());
        if ("REPLAY".equalsIgnoreCase(route)) {
            // prove the replay route yields the same membership before anything is written
            EligibilityRunService.Equivalence eq = runs.compare(day);
            if (!eq.equal()) {
                throw new IllegalStateException("Reconstruction routes disagree at day " + day
                        + "; delivery refused. " + String.join(" ; ", eq.differences()));
            }
        }
        rows = rowRepo.findByRunIdOrderByLocalDayAscSubjectPseudonymAsc(materialized.getId());

        List<EligibilityRow> globalRows = rows.stream()
                .filter(r -> r.getPlane() == PlaneType.GLOBAL)
                .filter(r -> r.getEligibility() == Eligibility.GLOBAL_AVAILABLE)
                .filter(r -> r.getVerdict() == Verdict.ELIGIBLE)
                .toList();
        if (globalRows.isEmpty() && rows.stream().noneMatch(r -> r.getPlane() == PlaneType.GLOBAL)) {
            throw new IllegalStateException("No rows to compile");
        }

        // only latest revisions and deduplicated stable ids may enter a delivery
        Map<String, EligibilityRow> latest = new LinkedHashMap<>();
        for (EligibilityRow r : globalRows) {
            String key = r.getSubjectPseudonym() + "|" + r.getStableId();
            EligibilityRow cur = latest.get(key);
            if (cur == null || r.getRevision() > cur.getRevision()
                    || (r.getRevision() == cur.getRevision() && r.getOccurredAt().isAfter(cur.getOccurredAt()))) {
                latest.put(key, r);
            }
        }
        List<EligibilityRow> members = latest.values().stream()
                .sorted(Comparator.comparing(EligibilityRow::getOccurredAt)
                        .thenComparing(EligibilityRow::getSubjectPseudonym))
                .toList();

        String versions = members.stream().map(EligibilityRow::getProtocolVersion).filter(v -> !v.isBlank())
                .distinct().sorted().collect(Collectors.joining(","));
        Set<String> projection = members.stream()
                .flatMap(r -> Text.stringSet(r.getProjectedFields()).stream())
                .collect(Collectors.toCollection(TreeSet::new));

        Delivery d = new Delivery();
        d.setCode(nextDeliveryCode(day));
        d.setTitle("Global analysis delivery — analysis day " + day);
        d.setAnalysisAt(world.analysisAt());
        d.setAnalysisDay(day);
        d.setRun(materialized);
        d.setApproval(approval);
        d.setPurpose(purpose);
        d.setStatus(DeliveryStatus.ACTIVE);
        d.setApprovedVersions(versions.isEmpty() ? "(none)" : versions);
        d.setFieldProjection(String.join(",", projection));
        d.setMemberFilter("approval=" + approval.getCode() + "; sites=" + approval.getSiteScope()
                + "; window=" + approval.getObservationFrom() + ".." + approval.getObservationTo()
                + "; purpose=" + purpose + "; plane=GLOBAL; eligibility=GLOBAL_AVAILABLE; latest_revision=true");
        d.setSourceWatermarks(eligibility.pinWatermarks(day));
        d.setLegalMemberCount(members.size());
        d.setCompiledAt(Instant.now());
        d.setCompiledBy(actor);
        d.setRoute(route.toUpperCase());
        d.setAccessRevoked(false);
        d.setContentCleared(false);

        // freeze summary: pseudonym digest + per-member stable id list (no identity)
        List<String> summaryLines = members.stream()
                .map(r -> r.getSubjectPseudonym() + ":" + r.getSiteCode() + ":" + r.getStableId() + "#"
                        + r.getRevision() + ":" + r.getOccurredAt() + ":" + Text.joinSorted(Text.stringSet(r.getProjectedFields())))
                .sorted()
                .toList();
        d.setMemberSummary(String.join("\n", summaryLines));
        d.setLegalMemberDigest(Text.sha256(String.join("|", summaryLines)));
        List<String> recordHashes = new ArrayList<>();
        for (EligibilityRow r : members) {
            recordHashes.add(Text.sha256(summaryLine(r)));
        }
        d.setContentDigest(Text.sha256(String.join("|", recordHashes.stream().sorted().toList())));
        d = deliveryRepo.save(d);

        for (EligibilityRow r : members) {
            DeliveryRecord rec = new DeliveryRecord();
            rec.setDelivery(d);
            rec.setSubjectPseudonym(r.getSubjectPseudonym());
            rec.setSiteCode(r.getSiteCode());
            rec.setRegion(regionOf(r));
            rec.setDomain(r.getDomain());
            rec.setStableId(r.getStableId());
            rec.setRevision(r.getRevision());
            rec.setOccurredAt(r.getOccurredAt());
            rec.setProtocolVersion(r.getProtocolVersion());
            rec.setProjectedJson(projectPayload(r));
            rec.setRecordHash(Text.sha256(summaryLine(r)));
            rec.setAccessRevoked(false);
            rec.setCleared(false);
            recordRepo.save(rec);
        }
        audit.record(actor, "DELIVERY_COMPILED", "Delivery", d.getCode(),
                members.size() + " members; versions=" + d.getApprovedVersions()
                        + "; fields=" + d.getFieldProjection() + "; digest=" + d.getContentDigest()
                        + "; route=" + d.getRoute());
        return d;
    }

    private String regionOf(EligibilityRow r) {
        return r.getSiteCode().contains("-") ? r.getSiteCode().substring(0, r.getSiteCode().indexOf('-')) : r.getSiteCode();
    }

    private String summaryLine(EligibilityRow r) {
        return r.getSubjectPseudonym() + ":" + r.getSiteCode() + ":" + r.getStableId() + "#" + r.getRevision()
                + ":" + r.getOccurredAt() + ":" + Text.joinSorted(Text.stringSet(r.getProjectedFields()));
    }

    /** Emit only the projected field groups; identity columns are never in this map. */
    private String projectPayload(EligibilityRow r) {
        Set<String> allowed = Text.stringSet(r.getProjectedFields());
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"pseudonym\":\"").append(r.getSubjectPseudonym()).append("\",");
        sb.append("\"domain\":\"").append(r.getDomain()).append("\",");
        sb.append("\"occurredAt\":\"").append(r.getOccurredAt()).append("\",");
        sb.append("\"protocol\":\"").append(r.getProtocolVersion()).append("\",");
        sb.append("\"fields\":[");
        sb.append(allowed.stream().map(f -> "\"" + f + "\"").collect(Collectors.joining(",")));
        sb.append("]}");
        return sb.toString();
    }

    private String nextDeliveryCode(int day) {
        String base = "DLV-D" + day;
        long n = deliveryRepo.findAll().stream().filter(x -> x.getCode().startsWith(base)).count();
        return base + "-" + String.format("%03d", n + 1);
    }

    @Transactional(readOnly = true)
    public List<Delivery> list() {
        return deliveryRepo.findAllByOrderByCompiledAtDesc();
    }

    @Transactional(readOnly = true)
    public Optional<Delivery> find(String code) {
        return deliveryRepo.findByCode(code);
    }

    @Transactional(readOnly = true)
    public List<DeliveryRecord> records(Delivery d) {
        return recordRepo.findByDeliveryIdOrderBySubjectPseudonymAscOccurredAtAsc(d.getId());
    }

    @Transactional(readOnly = true)
    public boolean canRead(Delivery d) {
        return !d.isAccessRevoked() && d.getStatus() != DeliveryStatus.ACCESS_REVOKED
                && d.getStatus() != DeliveryStatus.DESTROYED && !d.isContentCleared();
    }
}
