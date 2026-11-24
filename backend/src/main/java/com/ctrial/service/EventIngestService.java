package com.ctrial.service;

import com.ctrial.common.EventHash;
import com.ctrial.common.Text;
import com.ctrial.model.*;
import com.ctrial.model.Enums.*;
import com.ctrial.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Source event ledger ingestion.
 *
 * <p>Handles:
 * <ul>
 *   <li><b>Retransmission</b> — same (source system, domain, source seq): the
 *       existing row is returned, never duplicated. Conflicting payload for the
 *       same transport identity is quarantined.</li>
 *   <li><b>Revision</b> — a corrected event is a new revision linked to its
 *       predecessor; the predecessor stays for audit.</li>
 *   <li><b>Withdrawal vs correction race</b> — if a correction's occurrence time
 *       and a withdrawal's effective time are identical, or the correction is
 *       published after the withdrawal took effect with no approved tie-break,
 *       the correction is quarantined instead of being silently applied.</li>
 * </ul>
 */
@Service
public class EventIngestService {

    private final EventRepo eventRepo;
    private final ReceiptRepo receiptRepo;
    private final SubjectRepo subjectRepo;
    private final SiteRepo siteRepo;
    private final ConsentRepo consentRepo;
    private final StateService state;
    private final QuarantineService quarantine;
    private final TieBreakRepo tieBreakRepo;
    private final AuditService audit;

    public EventIngestService(EventRepo eventRepo, ReceiptRepo receiptRepo, SubjectRepo subjectRepo,
                              SiteRepo siteRepo, ConsentRepo consentRepo, StateService state,
                              QuarantineService quarantine, TieBreakRepo tieBreakRepo, AuditService audit) {
        this.tieBreakRepo = tieBreakRepo;
        this.eventRepo = eventRepo;
        this.receiptRepo = receiptRepo;
        this.subjectRepo = subjectRepo;
        this.siteRepo = siteRepo;
        this.consentRepo = consentRepo;
        this.state = state;
        this.quarantine = quarantine;
        this.audit = audit;
    }

    public record IngestRequest(String stableId, Integer revision, String pseudonym, Domain domain,
                               Instant occurredAt, Instant receivedAt, String sourceSystem, Long sourceSeq,
                               String protocolVersion, String fieldGroups, String payloadJson,
                               boolean voided, String voidReason) {
    }

    public record IngestResult(LocalEvent event, boolean duplicate, boolean quarantined,
                               boolean pendingAtSource, String message) {
    }

    @Transactional
    public IngestResult ingest(IngestRequest req) {
        if (req.stableId() == null || req.stableId().isBlank()) {
            throw new IllegalArgumentException("stableId is required");
        }
        if (req.sourceSystem() == null || req.sourceSeq() == null) {
            throw new IllegalArgumentException("sourceSystem and sourceSeq are required for the transport identity");
        }
        Subject subject = subjectRepo.findByPseudonym(req.pseudonym())
                .orElseThrow(() -> new IllegalArgumentException("Unknown pseudonym " + req.pseudonym()));
        int revision = req.revision() == null ? 1 : req.revision();
        Instant occurredAt = req.occurredAt() == null ? req.receivedAt() : req.occurredAt();
        Instant receivedAt = req.receivedAt() == null ? occurredAt : req.receivedAt();
        Instant ingestedAt = Instant.now();
        String normalizedFields = req.fieldGroups() == null ? ""
                : Text.csvStrings(Arrays.asList(req.fieldGroups().split(",")));
        String hash = EventHash.of(req.stableId(), revision, req.domain().name(), occurredAt,
                req.protocolVersion(), normalizedFields, req.payloadJson(), req.voided());

        // 1. exact transport replay
        Optional<LocalEvent> byTransport = eventRepo.findBySourceSystemAndDomainAndSourceSeq(
                req.sourceSystem(), req.domain(), req.sourceSeq());
        if (byTransport.isPresent()) {
            LocalEvent existing = byTransport.get();
            boolean sameContent = existing.getContentHash().equals(hash);
            TransportReceipt receipt = new TransportReceipt();
            receipt.setSourceSystem(req.sourceSystem());
            receipt.setDomain(req.domain().name());
            receipt.setSourceSeq(req.sourceSeq());
            receipt.setStableId(req.stableId());
            receipt.setRevision(revision);
            receipt.setContentHash(hash);
            receipt.setAckedAt(ingestedAt);
            receipt.setWasDuplicate(true);
            receipt.setEventId(existing.getId());
            receiptRepo.save(receipt);
            if (!sameContent) {
                QuarantineItem q = quarantine.open(QuarantineKind.DUPLICATE_HASH_CONFLICT, req.stableId(),
                        req.pseudonym(), existing,
                        "Transport identity " + req.sourceSystem() + "/" + req.domain() + "/" + req.sourceSeq()
                                + " re-sent with different content. Stored hash=" + existing.getContentHash()
                                + " incoming hash=" + hash + ". Neither version was applied.");
                return new IngestResult(existing, true, true, false,
                        "Duplicate transport identity with conflicting content quarantined as " + q.getCode());
            }
            state.touchWatermarks(req.sourceSystem(), ingestedAt);
            return new IngestResult(existing, true, false, false,
                    "Retransmission of " + req.stableId() + "#" + revision + " acknowledged; counted once.");
        }

        // 2. clinical identity already present under a different transport frame?
        Optional<LocalEvent> byClinical = eventRepo.findByStableIdAndRevision(req.stableId(), revision);
        if (byClinical.isPresent()) {
            LocalEvent existing = byClinical.get();
            if (existing.getContentHash().equals(hash)) {
                return new IngestResult(existing, true, false, false,
                        "Same clinical revision already ingested under sequence " + existing.getSourceSeq()
                                + "; counted once.");
            }
            // same clinical slot, different content -> quarantine, do not overwrite
            QuarantineItem q = quarantine.open(QuarantineKind.DUPLICATE_HASH_CONFLICT, req.stableId(),
                    req.pseudonym(), existing,
                    "Clinical identity " + req.stableId() + "#" + revision
                            + " arrived twice with different content from " + req.sourceSystem()
                            + " seq " + req.sourceSeq() + ". Existing content retained; new content held.");
            return new IngestResult(existing, true, true, false,
                    "Conflicting duplicate held in quarantine as " + q.getCode());
        }

        // 3. withdrawal vs correction race
        List<ConsentEntry> consents = consentRepo.findBySubjectIdOrderByEffectiveFromAscRecordedAtAsc(subject.getId());
        Optional<ConsentEntry> sameInstantWithdrawal = consents.stream()
                .filter(c -> c.getKind() == ConsentKind.WITHDRAWAL)
                .filter(c -> c.getEffectiveFrom().equals(occurredAt) || c.getEffectiveFrom().equals(receivedAt))
                .findFirst();
        if (sameInstantWithdrawal.isPresent()) {
            TieBreakRule tb = tieBreakRepo.findByActiveTrueOrderByCodeAsc().stream().findFirst().orElse(null);
            if (tb != null && "CORRECTION_WINS".equalsIgnoreCase(tb.getPrecedence())) {
                audit.record("system:ingest", "RACE_ADJUDICATED", "LocalEvent", req.stableId(),
                        "Correction admitted ahead of withdrawal " + sameInstantWithdrawal.get().getId()
                                + " under registered rule " + tb.getCode() + " (" + tb.getRationale() + ")");
                // fall through and apply the correction, then quarantine a note for transparency
                QuarantineItem q = quarantine.open(QuarantineKind.RACE_WITHDRAWAL_CORRECTION, req.stableId(),
                        req.pseudonym(), null,
                        "Correction of " + req.stableId() + " collided with withdrawal "
                                + sameInstantWithdrawal.get().getId() + "; adjudicated CORRECTION_WINS under "
                                + tb.getCode() + " (" + tb.getRationale() + "). Recorded for review.");
                // continue to insert
            } else if (tb != null && "WITHDRAWAL_WINS".equalsIgnoreCase(tb.getPrecedence())) {
                QuarantineItem q = quarantine.open(QuarantineKind.RACE_WITHDRAWAL_CORRECTION, req.stableId(),
                        req.pseudonym(), null,
                        "Correction of " + req.stableId() + " blocked ahead of withdrawal "
                                + sameInstantWithdrawal.get().getId() + " under registered rule " + tb.getCode()
                                + " (" + tb.getRationale() + "). Correction not applied.");
                return new IngestResult(null, false, true, false,
                        "Withdrawal/correction race resolved WITHDRAWAL_WINS under " + tb.getCode()
                                + "; correction held as " + q.getCode() + " and not applied.");
            } else {
                QuarantineItem q = quarantine.open(QuarantineKind.RACE_WITHDRAWAL_CORRECTION, req.stableId(),
                        req.pseudonym(), null,
                        "Correction of " + req.stableId() + " carries an effective instant equal to withdrawal "
                                + sameInstantWithdrawal.get().getId() + " (" + sameInstantWithdrawal.get().getPurposeLabelForAudit()
                                + "). No registered tie-break rule orders these; isolated rather than guessed.");
                return new IngestResult(null, false, true, false,
                        "Withdrawal/correction race isolated as " + q.getCode() + "; neither side applied.");
            }
        }

        LocalEvent e = new LocalEvent();
        e.setStableId(req.stableId());
        e.setRevision(revision);
        e.setSubject(subject);
        e.setDomain(req.domain());
        e.setOccurredAt(occurredAt);
        e.setReceivedAt(receivedAt);
        e.setIngestedAt(ingestedAt);
        e.setSourceSystem(req.sourceSystem());
        e.setSourceSeq(req.sourceSeq());
        e.setProtocolVersion(req.protocolVersion() == null ? "" : req.protocolVersion());
        e.setFieldGroups(normalizedFields);
        e.setPayloadJson(req.payloadJson());
        e.setVoided(req.voided());
        e.setVoidReason(req.voidReason());
        e.setContentHash(hash);
        if (revision > 1) {
            Optional<LocalEvent> prev = eventRepo.findByStableIdAndRevision(req.stableId(), revision - 1);
            if (prev.isPresent()) {
                e.setSupersedesEventId(prev.get().getId());
            }
        }
        e = eventRepo.save(e);

        TransportReceipt receipt = new TransportReceipt();
        receipt.setSourceSystem(req.sourceSystem());
        receipt.setDomain(req.domain().name());
        receipt.setSourceSeq(req.sourceSeq());
        receipt.setStableId(req.stableId());
        receipt.setRevision(revision);
        receipt.setContentHash(hash);
        receipt.setAckedAt(ingestedAt);
        receipt.setWasDuplicate(false);
        receipt.setEventId(e.getId());
        receiptRepo.save(receipt);

        boolean pendingAtSource = state.touchWatermarks(req.sourceSystem(), ingestedAt);
        audit.record("system:ingest", "EVENT_INGESTED", "LocalEvent", e.naturalKey(),
                req.pseudonym() + " " + req.domain() + " sourceSeq=" + req.sourceSeq()
                        + (pendingAtSource ? " (above source-confirmed watermark: UNCONFIRMED until source advances)" : ""));
        return new IngestResult(e, false, false, pendingAtSource,
                "Ingested " + e.naturalKey() + " from " + req.sourceSystem() + "#" + req.sourceSeq()
                        + (pendingAtSource ? "; held as UNCONFIRMED until the source-confirmed watermark advances." : "."));
    }
}
