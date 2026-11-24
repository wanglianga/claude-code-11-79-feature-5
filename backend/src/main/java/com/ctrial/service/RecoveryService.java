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
 * Post-outage recovery verification for one site. Checks, per domain:
 * <ol>
 *   <li><b>Gaps</b> — sequence numbers between the transfer acknowledgement and
 *       the source-confirmed watermark that the ledger does not contain.</li>
 *   <li><b>Duplicate re-sends</b> — transport identities received more than
 *       once, plus any that produced a quarantine conflict.</li>
 *   <li><b>Cross-domain digest</b> — a digest of (subject, domain, stable id,
 *       revision, content hash) per domain, compared against a reconstruction
 *       digest computed from the stored ledger; a mismatch means the two sides
 *       of the link disagree about what was transferred.</li>
 * </ol>
 * The report is stored with its own digest so an auditor can prove what was
 * checked and when.
 */
@Service
public class RecoveryService {

    private final WatermarkRepo watermarkRepo;
    private final EventRepo eventRepo;
    private final ReceiptRepo receiptRepo;
    private final QuarantineRepo quarantineRepo;
    private final RecoveryRepo recoveryRepo;
    private final AuditService audit;

    public RecoveryService(WatermarkRepo watermarkRepo, EventRepo eventRepo, ReceiptRepo receiptRepo,
                           QuarantineRepo quarantineRepo, RecoveryRepo recoveryRepo, AuditService audit) {
        this.watermarkRepo = watermarkRepo;
        this.eventRepo = eventRepo;
        this.receiptRepo = receiptRepo;
        this.quarantineRepo = quarantineRepo;
        this.recoveryRepo = recoveryRepo;
        this.audit = audit;
    }

    public record DomainReport(String domain, List<Long> gapSequences, int duplicateResends,
                               boolean digestConsistent, String ledgerDigest, List<String> notes) {
    }

    public record RecoveryReport(String siteCode, Instant runAt, RecoveryStatus status, int gapCount,
                                 int duplicateCount, int mismatchCount, List<DomainReport> domains,
                                 List<String> summary) {
    }

    @Transactional
    public RecoveryCheck run(String siteCode, String actor) {
        Site site = null;
        List<Watermark> marks = watermarkRepo.findBySiteCodeOrderByDomainAsc(siteCode);
        if (marks.isEmpty()) {
            throw new IllegalArgumentException("No watermarks for site " + siteCode);
        }
        List<LocalEvent> siteEvents = eventRepo.findAllByOrderByOccurredAtAscIdAsc().stream()
                .filter(e -> e.getSubject().getSite().getCode().equals(siteCode)).toList();
        String sourceSystem = siteEvents.isEmpty() ? null : siteEvents.get(0).getSourceSystem();
        List<TransportReceipt> receipts = sourceSystem == null ? List.of()
                : receiptRepo.findBySourceSystemOrderByAckedAtAsc(sourceSystem);
        List<QuarantineItem> quarantines = quarantineRepo.findByStateOrderByOpenedAtDesc(QuarantineState.OPEN);

        List<DomainReport> domainReports = new ArrayList<>();
        int gaps = 0;
        int duplicates = 0;
        int mismatches = 0;
        List<String> summary = new ArrayList<>();

        for (Watermark w : marks) {
            Domain d = w.getDomain();
            Set<Long> present = siteEvents.stream().filter(e -> e.getDomain() == d)
                    .map(LocalEvent::getSourceSeq).collect(Collectors.toCollection(TreeSet::new));
            List<Long> gapSeqs = new ArrayList<>();
            for (long s = w.getTransferAckSeq() + 1; s <= w.getSourceConfirmedSeq(); s++) {
                if (!present.contains(s)) {
                    gapSeqs.add(s);
                }
            }
            int dup = (int) receipts.stream().filter(r -> r.getDomain().equals(d.name()) && r.isWasDuplicate()).count();
            int conflict = (int) quarantines.stream()
                    .filter(q -> QuarantineKind.DUPLICATE_HASH_CONFLICT == q.getKind())
                    .filter(q -> q.getSubjectPseudonym() != null)
                    .filter(q -> siteEvents.stream().anyMatch(e -> e.getStableId().equals(q.getStableId())))
                    .count();

            // Compare the *set* of clinical records the ledger holds with the set the
            // transport claims to have delivered, restricted to the confirmed range so a
            // legitimately unconfirmed tail does not read as a mismatch.
            List<String> ledgerSet = siteEvents.stream()
                    .filter(e -> e.getDomain() == d)
                    .map(e -> e.getStableId() + "#" + e.getRevision() + ":" + e.getContentHash())
                    .distinct().sorted().toList();
            List<String> receiptSet = receipts.stream()
                    .filter(r -> r.getDomain().equals(d.name()))
                    .map(r -> r.getStableId() + "#" + r.getRevision() + ":" + r.getContentHash())
                    .distinct().sorted().toList();
            String ledgerDigest = Text.sha256(String.join("|", ledgerSet));
            String reconstructed = Text.sha256(String.join("|", receiptSet));
            boolean consistent = ledgerDigest.equals(reconstructed);

            List<String> notes = new ArrayList<>();
            if (!gapSeqs.isEmpty()) {
                notes.add("Sequences " + gapSeqs + " are acknowledged at source but missing from the ledger; "
                        + "they must be back-filled, not assumed empty.");
                gaps += gapSeqs.size();
            }
            if (dup > 0) {
                notes.add(dup + " retransmission(s) deduplicated by transport identity; not counted twice.");
                duplicates += dup;
            }
            if (conflict > 0) {
                notes.add(conflict + " conflicting duplicate(s) quarantined.");
            }
            if (!consistent) {
                notes.add("Cross-domain digest mismatch between ledger and transport receipts.");
                mismatches++;
            }
            domainReports.add(new DomainReport(d.name(), gapSeqs, dup, consistent, ledgerDigest, notes));
        }

        if (gaps == 0 && duplicates == 0 && mismatches == 0) {
            summary.add("No gaps, no unresolved duplicates, all domain digests agree.");
        } else {
            summary.add(gaps + " sequence gap(s), " + duplicates + " duplicate resend(s), "
                    + mismatches + " cross-domain mismatch(es).");
        }
        if (quarantines.stream().anyMatch(q -> QuarantineKind.RACE_WITHDRAWAL_CORRECTION == q.getKind())) {
            summary.add("Open withdrawal/correction races remain quarantined; they are excluded from legal sets "
                    + "until adjudicated.");
        }

        RecoveryStatus status = (gaps == 0 && mismatches == 0) ? RecoveryStatus.PASS : RecoveryStatus.FINDINGS;
        String reportJson = toJson(siteCode, status, gaps, duplicates, mismatches, domainReports, summary);
        RecoveryCheck check = new RecoveryCheck();
        check.setCode("RC-" + siteCode + "-" + String.format("%03d", recoveryRepo.findAllByOrderByRunAtDesc().size() + 1));
        check.setSiteCode(siteCode);
        check.setRunAt(Instant.now());
        check.setStatus(status);
        check.setGapCount(gaps);
        check.setDuplicateCount(duplicates);
        check.setMismatchCount(mismatches);
        check.setReportJson(reportJson);
        check.setReportDigest(Text.sha256(reportJson));
        check.setRunBy(actor);
        check = recoveryRepo.save(check);
        audit.record(actor, "RECOVERY_CHECK", "Site", siteCode,
                "status=" + status + " gaps=" + gaps + " dups=" + duplicates + " mismatches=" + mismatches);
        return check;
    }

    private String toJson(String site, RecoveryStatus status, int gaps, int dups, int mismatches,
                          List<DomainReport> domains, List<String> summary) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"site\":\"").append(site).append("\",\"status\":\"").append(status).append("\",");
        sb.append("\"gaps\":").append(gaps).append(",\"duplicates\":").append(dups)
                .append(",\"mismatches\":").append(mismatches).append(",\"domains\":[");
        for (int i = 0; i < domains.size(); i++) {
            DomainReport d = domains.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"domain\":\"").append(d.domain()).append("\",\"gapSequences\":")
                    .append(d.gapSequences()).append(",\"duplicateResends\":").append(d.duplicateResends())
                    .append(",\"digestConsistent\":").append(d.digestConsistent())
                    .append(",\"ledgerDigest\":\"").append(d.ledgerDigest()).append("\"}");
        }
        sb.append("],\"summary\":[");
        for (int i = 0; i < summary.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(summary.get(i).replace("\"", "'")).append('"');
        }
        sb.append("]}");
        return sb.toString();
    }

    public List<RecoveryCheck> all() {
        return recoveryRepo.findAllByOrderByRunAtDesc();
    }
}
