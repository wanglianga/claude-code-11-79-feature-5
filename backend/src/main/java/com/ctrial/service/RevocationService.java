package com.ctrial.service;

import com.ctrial.model.*;
import com.ctrial.model.Enums.*;
import com.ctrial.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Revocation propagation. When a withdrawal or protocol change lands:
 * <ol>
 *   <li>Stop future queries: mark affected materialised runs STALE.</li>
 *   <li>Revoke access to any already-generated delivery that contains records
 *       the withdrawal now forbids — <b>immediately</b>, but without claiming
 *       the delivery never existed.</li>
 *   <li>Apply the pre-registered disposition rule
 *       (RETAIN_RESTRICTED / DESTROY_AFTER) to the affected derived rows.</li>
 *   <li>Keep the original delivery summary and its digests intact for audit.</li>
 * </ol>
 * What is forbidden is presenting a historical delivery as if it had been
 * erased, or leaving its content readable after the withdrawal.
 */
@Service
public class RevocationService {

    private final DeliveryRepo deliveryRepo;
    private final DeliveryRecordRepo recordRepo;
    private final DispositionRuleRepo ruleRepo;
    private final DispositionExecutionRepo execRepo;
    private final ConsentRepo consentRepo;
    private final SubjectRepo subjectRepo;
    private final EligibilityRunService runs;
    private final AuditService audit;

    public RevocationService(DeliveryRepo deliveryRepo, DeliveryRecordRepo recordRepo,
                             DispositionRuleRepo ruleRepo, DispositionExecutionRepo execRepo,
                             ConsentRepo consentRepo, SubjectRepo subjectRepo,
                             EligibilityRunService runs, AuditService audit) {
        this.deliveryRepo = deliveryRepo;
        this.recordRepo = recordRepo;
        this.ruleRepo = ruleRepo;
        this.execRepo = execRepo;
        this.consentRepo = consentRepo;
        this.subjectRepo = subjectRepo;
        this.runs = runs;
        this.audit = audit;
    }

    public record RevocationOutcome(String subjectPseudonym, Instant effectiveFrom, Purpose purpose,
                                    int runsInvalidated, int deliveriesRevoked, int recordsRestricted,
                                    int recordsDestroyed, int executionsScheduled,
                                    List<String> deliveryCodes, List<String> dispositions, String summary) {
    }

    /**
     * Propagate a withdrawal entry. {@code purpose} is the withdrawn purpose;
     * records occurring before the effective instant keep their restricted
     * basis, records at or after it are not usable at all.
     */
    @Transactional
    public RevocationOutcome propagateWithdrawal(Long consentEntryId) {
        ConsentEntry entry = consentRepo.findById(consentEntryId)
                .orElseThrow(() -> new IllegalArgumentException("Consent entry " + consentEntryId + " not found"));
        if (entry.getKind() != ConsentKind.WITHDRAWAL) {
            throw new IllegalArgumentException("Entry " + consentEntryId + " is not a withdrawal");
        }
        Subject subject = entry.getSubject();
        Instant effective = entry.getEffectiveFrom();
        Set<Purpose> withdrawnPurposes = new LinkedHashSet<>(entry.purposeSet());
        if (withdrawnPurposes.isEmpty()) {
            withdrawnPurposes.add(Purpose.ALL);
        }
        Purpose primary = withdrawnPurposes.contains(Purpose.GLOBAL_ANALYSIS) || withdrawnPurposes.contains(Purpose.ALL)
                ? Purpose.GLOBAL_ANALYSIS : withdrawnPurposes.iterator().next();

        int invalidated = runs.invalidateRuns("CONSENT_WITHDRAWAL", effective,
                "subject " + subject.getPseudonym() + " withdrew " + entry.getPurposeLabelForAudit(),
                subject.getPseudonym());

        List<DispositionRule> rules = ruleRepo.findByActiveTrueOrderByCodeAsc();
        List<String> deliveryCodes = new ArrayList<>();
        List<String> dispositionSummaries = new ArrayList<>();
        int revokedRecords = 0;
        int destroyedRecords = 0;
        int scheduledExecutions = 0;

        for (Delivery d : deliveryRepo.findAllByOrderByCompiledAtDesc()) {
            List<DeliveryRecord> affected = recordRepo
                    .findByDeliveryIdAndSubjectPseudonym(d.getId(), subject.getPseudonym());
            if (affected.isEmpty()) {
                continue;
            }
            // New access stops for *every* affected row, not only those at/after the
            // effective instant: a record held at occurrence may be retained, but it
            // must not remain readable through a historical delivery.
            List<DeliveryRecord> applicable = new ArrayList<>(affected);
            int heldAtOccurrence = (int) affected.stream()
                    .filter(r -> r.getOccurredAt().isBefore(effective)).count();

            // choose the most specific registered rule for this subject's site
            DispositionRule rule = chooseRule(rules, subject.getSite().getCode(), primary);
            RuleAction action = rule == null ? RuleAction.RETAIN_RESTRICTED : rule.getAction();

            d.setAccessRevoked(true);
            d.setStatus(DeliveryStatus.ACCESS_REVOKED);
            d.setRevokedAt(Instant.now());
            d.setRevocationScope("subject " + subject.getPseudonym() + " rows at/after " + effective);
            d.setRevokedReason("Withdrawal " + entry.getId() + " (" + entry.getPurposeLabelForAudit()
                    + ") effective " + effective + "; access to affected rows revoked. "
                    + "Original delivery summary retained for audit; content disposition follows "
                    + (rule == null ? "default restricted retention" : rule.getCode() + " (" + action + ")") + ".");
            d.setRevocationAffectedRows(applicable.size());
            deliveryRepo.save(d);
            deliveryCodes.add(d.getCode());

            for (DeliveryRecord r : applicable) {
                r.setAccessRevoked(true);
                r.setRevokedAt(Instant.now());
                boolean held = r.getOccurredAt().isBefore(effective);
                if (action == RuleAction.DESTROY_AFTER) {
                    d.setDestroyedAt(Instant.now());
                    d.setStatus(DeliveryStatus.DESTROYED);
                    clearContent(r);
                    destroyedRecords++;
                } else {
                    // restricted retention: the row is isolated (access revoked) but not erased;
                    // whether that is lawful depends on the registered basis, which is recorded below
                    r.setCleared(false);
                    revokedRecords++;
                }
                recordRepo.save(r);
            }
            if (rule != null) {
                DispositionExecution ex = new DispositionExecution();
                ex.setRule(rule);
                ex.setDelivery(d);
                ex.setSubject(subject);
                ex.setState(action == RuleAction.DESTROY_AFTER ? DispositionState.EXECUTED : DispositionState.APPLIED);
                ex.setAffectedRows(applicable.size());
                ex.setCreatedAt(Instant.now());
                if (action == RuleAction.DESTROY_AFTER) {
                    ex.setExecutedAt(Instant.now());
                    ex.setDetail("Destroyed " + applicable.size() + " derived row(s) under " + rule.getCode()
                            + " (" + rule.getLegalBasis() + "); delivery summary and digests preserved.");
                } else {
                    Instant until = rule.getRetainYears() == null ? null
                            : effective.plus(rule.getRetainYears() * 365L, ChronoUnit.DAYS);
                    ex.setScheduledFor(until);
                    ex.setDetail("Restricted retention of " + applicable.size() + " derived row(s) ("
                            + heldAtOccurrence + " held at occurrence) until "
                            + (until == null ? "(unspecified)" : until) + " under " + rule.getCode()
                            + " (" + rule.getLegalBasis() + "); access revoked, content isolated, "
                            + "original delivery summary and digests preserved for audit.");
                    if (rule.getDestroyAfterDays() != null) {
                        scheduledExecutions++;
                    }
                }
                execRepo.save(ex);
                dispositionSummaries.add(rule.getCode() + "=" + action + " x" + applicable.size());
            } else {
                dispositionSummaries.add("DEFAULT_RETAIN_RESTRICTED x" + applicable.size());
            }
        }

        String summary = "Withdrawal " + entry.getId() + " for " + subject.getPseudonym()
                + ": " + invalidated + " snapshot run(s) invalidated, " + deliveryCodes.size()
                + " delivery(ies) access-revoked, " + revokedRecords + " row(s) restricted, "
                + destroyedRecords + " row(s) destroyed. Historical delivery summaries are preserved, "
                + "not rewritten.";
        audit.record("system:propagation", "WITHDRAWAL_PROPAGATED", "ConsentEntry", String.valueOf(entry.getId()),
                summary);
        return new RevocationOutcome(subject.getPseudonym(), effective, primary, invalidated,
                deliveryCodes.size(), revokedRecords, destroyedRecords, scheduledExecutions,
                deliveryCodes, dispositionSummaries, summary);
    }

    private void clearContent(DeliveryRecord r) {
        r.setProjectedJson(null);
        r.setCleared(true);
    }

    private DispositionRule chooseRule(List<DispositionRule> rules, String site, Purpose purpose) {
        DispositionRule best = null;
        for (DispositionRule r : rules) {
            if (!r.matches(site, purpose)) {
                continue;
            }
            if (r.getScope() == DispositionScope.SITE) {
                return r;
            }
            best = r;
        }
        return best;
    }
}
