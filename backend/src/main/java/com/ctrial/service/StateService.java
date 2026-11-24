package com.ctrial.service;

import com.ctrial.model.*;
import com.ctrial.model.Enums.*;
import com.ctrial.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Owns the three watermark counters per (site, domain) and the link state.
 *
 * <p>Counters are independent and are advanced only by explicit operations so
 * that the UI can reproduce every intermediate state:
 * <ul>
 *   <li><b>source confirmed</b> — site system durably published the sequence;</li>
 *   <li><b>transfer ack</b> — transport acknowledged receipt;</li>
 *   <li><b>analysis</b> — sequence admitted to the global analysis plane.</li>
 * </ul>
 * Ingesting an event advances source-confirmed and transfer-ack (we now hold
 * the bytes) but never the analysis plane. Holding transfer ack back is what
 * produces a visible unconfirmed gap for records that exist at source.
 */
@Service
public class StateService {

    private final WatermarkRepo watermarkRepo;
    private final EventRepo eventRepo;
    private final SiteRepo siteRepo;
    private final AuditService audit;

    public StateService(WatermarkRepo watermarkRepo, EventRepo eventRepo, SiteRepo siteRepo, AuditService audit) {
        this.watermarkRepo = watermarkRepo;
        this.eventRepo = eventRepo;
        this.siteRepo = siteRepo;
        this.audit = audit;
    }

    @Transactional
    public Watermark row(String siteCode, Domain domain) {
        return watermarkRepo.findBySiteCodeAndDomain(siteCode, domain)
                .orElseGet(() -> {
                    Watermark w = new Watermark();
                    w.setSiteCode(siteCode);
                    w.setDomain(domain);
                    w.setSourceConfirmedSeq(0);
                    w.setTransferAckSeq(0);
                    w.setAnalysisSeq(0);
                    w.setUpdatedAt(Instant.now());
                    return watermarkRepo.save(w);
                });
    }

    /**
     * Note a newly ingested record. Ingest writes to the <b>source ledger</b>
     * only; it deliberately does not advance any watermark. Publication,
     * transfer acknowledgement and analysis folding are separate, auditable
     * operations, which is what lets a link stay stuck at a lower sequence
     * while records keep accruing at source. Returns true when the new record
     * sits above the analysis watermark (i.e. is UNCONFIRMED for the global
     * plane).
     */
    @Transactional
    public boolean touchWatermarks(String sourceSystem, Instant at) {
        List<Site> sites = siteRepo.findAllByOrderByCodeAsc().stream()
                .filter(s -> s.getSourceSystem().equals(sourceSystem)).toList();
        boolean pending = false;
        for (Site site : sites) {
            for (Domain d : Domain.values()) {
                long maxSeq = eventRepo.findAllByOrderByOccurredAtAscIdAsc().stream()
                        .filter(e -> e.getSubject().getSite().getCode().equals(site.getCode()))
                        .filter(e -> e.getDomain() == d)
                        .mapToLong(LocalEvent::getSourceSeq).max().orElse(0);
                if (maxSeq == 0) {
                    continue;
                }
                Watermark w = row(site.getCode(), d);
                if (maxSeq > w.getAnalysisSeq()) {
                    pending = true;
                }
            }
        }
        return pending;
    }

    @Transactional
    public Watermark confirmSource(String site, Domain domain, long seq, String actor) {
        Watermark w = row(site, domain);
        long v = Math.max(w.getSourceConfirmedSeq(), seq);
        w.setSourceConfirmedSeq(v);
        w.setSourceConfirmedAt(Instant.now());
        w.setUpdatedAt(Instant.now());
        watermarkRepo.save(w);
        audit.record(actor, "WATERMARK_SOURCE_CONFIRMED", "Watermark", site + "/" + domain,
                "Source confirmed through sequence " + v);
        return w;
    }

    @Transactional
    public Watermark ackTransfer(String site, Domain domain, long seq, String actor) {
        Watermark w = row(site, domain);
        if (seq > w.getSourceConfirmedSeq()) {
            throw new IllegalArgumentException("Cannot acknowledge transfer beyond source-confirmed sequence "
                    + w.getSourceConfirmedSeq());
        }
        w.setTransferAckSeq(Math.max(w.getTransferAckSeq(), seq));
        w.setUpdatedAt(Instant.now());
        watermarkRepo.save(w);
        audit.record(actor, "WATERMARK_TRANSFER_ACKED", "Watermark", site + "/" + domain,
                "Transfer acknowledged through sequence " + w.getTransferAckSeq());
        return w;
    }

    /** Explicitly hold the transfer acknowledgement at a level (used to model a stuck link). */
    @Transactional
    public Watermark holdTransfer(String site, Domain domain, long seq, String actor) {
        Watermark w = row(site, domain);
        w.setTransferAckSeq(seq);
        w.setUpdatedAt(Instant.now());
        watermarkRepo.save(w);
        audit.record(actor, "WATERMARK_TRANSFER_HELD", "Watermark", site + "/" + domain,
                "Transfer acknowledgement pinned at sequence " + seq
                        + "; sequences above it are UNCONFIRMED, not zero.");
        return w;
    }

    @Transactional
    public Watermark foldAnalysis(String site, Domain domain, long seq, Instant analysisAt, String actor) {
        Watermark w = row(site, domain);
        long cap = Math.min(seq, w.getTransferAckSeq());
        w.setAnalysisSeq(Math.max(w.getAnalysisSeq(), cap));
        w.setAnalysisAt(analysisAt);
        w.setUpdatedAt(Instant.now());
        watermarkRepo.save(w);
        audit.record(actor, "WATERMARK_ANALYSIS_FOLDED", "Watermark", site + "/" + domain,
                "Analysis plane folded through sequence " + w.getAnalysisSeq()
                        + (cap < seq ? " (capped at transfer ack " + w.getTransferAckSeq() + ")" : ""));
        return w;
    }

    @Transactional
    public Watermark freezeAnalysis(String site, Domain domain, long seq, String actor) {
        Watermark w = row(site, domain);
        w.setAnalysisSeq(seq);
        w.setAnalysisAt(Instant.now());
        w.setUpdatedAt(Instant.now());
        watermarkRepo.save(w);
        audit.record(actor, "WATERMARK_ANALYSIS_PINNED", "Watermark", site + "/" + domain,
                "Analysis plane explicitly pinned at " + seq);
        return w;
    }

    @Transactional
    public void breakLink(String site, String actor, String reason) {
        Instant now = Instant.now();
        for (Watermark w : watermarkRepo.findBySiteCodeOrderByDomainAsc(site)) {
            w.setLinkBrokenAt(now);
            w.setLinkRestoredAt(null);
            w.setUpdatedAt(now);
            watermarkRepo.save(w);
        }
        audit.record(actor, "LINK_BROKEN", "Site", site, "Link interruption recorded: " + reason);
    }

    @Transactional
    public void restoreLink(String site, String actor) {
        Instant now = Instant.now();
        for (Watermark w : watermarkRepo.findBySiteCodeOrderByDomainAsc(site)) {
            w.setLinkRestoredAt(now);
            w.setUpdatedAt(now);
            watermarkRepo.save(w);
        }
        audit.record(actor, "LINK_RESTORED", "Site", site,
                "Link restored; a recovery check is required before trusting the gap.");
    }

    public List<Watermark> all() {
        return watermarkRepo.findAllByOrderBySiteCodeAscDomainAsc();
    }

    public List<Watermark> forSite(String site) {
        return watermarkRepo.findBySiteCodeOrderByDomainAsc(site);
    }
}
