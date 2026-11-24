package com.ctrial.service;

import com.ctrial.model.DataUseApproval;
import com.ctrial.model.Enums.Eligibility;
import com.ctrial.model.Enums.PlaneType;
import com.ctrial.model.Enums.Purpose;
import com.ctrial.model.Enums.Verdict;
import com.ctrial.model.LocalEvent;
import com.ctrial.model.Watermark;
import com.ctrial.service.dto.EvalResult;

import java.time.Instant;
import java.util.*;

/**
 * Pure rule engine shared by the materialised and the replay reconstruction
 * routes. It takes a candidate record plus exactly the facts that were
 * effective at the analysis instant and returns one of the three availability
 * outcomes with a full reason trace.
 *
 * <p>Deliberately free of persistence: both routes feed it the same facts, and
 * the equivalence test asserts byte-identical results. The "intersection, not
 * union" rule is implemented literally: a record is globally available only if
 * every gate passes; any single failing gate blocks, any undeterminable gate
 * pends.
 */
public final class EligibilityEngine {

    public static final String OK = "OK";

    private EligibilityEngine() {
    }

    /** Everything the engine needs, already filtered to the analysis instant. */
    public record Facts(
            LocalEvent event,
            String siteCode,
            String siteRegion,
            boolean chinaResident,
            String destinationRegion,
            Purpose queryPurpose,
            PlaneType plane,
            Instant analysisAt,
            DataUseApproval approval,
            Watermark watermark,
            List<com.ctrial.model.ConsentEntry> consentEntries,
            List<com.ctrial.model.ProtocolVersion> protocolVersions,
            boolean superseded,
            boolean retentionRulePresent
    ) {
    }

    public static EvalResult evaluate(Facts f) {
        if (f.event() == null) {
            return result(Eligibility.PENDING, Verdict.UNDETERMINED, PlaneType.GLOBAL,
                    List.of("NO_CANDIDATE"), List.of("No candidate record was supplied."),
                    null, Set.of(), false);
        }
        if (f.superseded()) {
            return result(Eligibility.BLOCKED, Verdict.NOT_ELIGIBLE, f.plane(),
                    List.of("EVENT_SUPERSEDED"),
                    List.of("A newer revision of this record exists; this revision is retained for audit only."),
                    f.event().getProtocolVersion(), Set.of(), false);
        }
        if (f.event().isVoided()) {
            return result(Eligibility.BLOCKED, Verdict.NOT_ELIGIBLE, f.plane(),
                    List.of("EVENT_VOIDED"),
                    List.of("Record was withdrawn/voided at source" + suffix(f.event().getVoidReason()) + "."),
                    f.event().getProtocolVersion(), Set.of(), false);
        }

        List<String> codes = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Set<String> reasons = new LinkedHashSet<>();

        Set<String> eventFields = upperCsv(f.event().getFieldGroups());

        // ---------- 1. protocol scope ----------
        ProtocolResolution proto = resolveProtocol(f, eventFields);
        if (proto.pending()) {
            return result(Eligibility.PENDING, Verdict.UNDETERMINED, f.plane(),
                    proto.codeList(), proto.noteList(), proto.version(), Set.of(), false);
        }
        if (proto.blocked()) {
            return result(Eligibility.BLOCKED, Verdict.NOT_ELIGIBLE, f.plane(),
                    proto.codeList(), proto.noteList(), null, Set.of(), false);
        }
        Set<String> protocolFields = proto.fieldGroups();
        reasons.add("PROTOCOL_" + proto.version());
        reasons.add("PROTOCOL_EFFECTIVE");

        // ---------- 2. approval envelope ----------
        DataUseApproval ap = f.approval();
        if (ap == null) {
            return blocked(f, "APPROVAL_MISSING", "No approved data-use envelope covers this query.", proto);
        }
        if (!ap.coversSite(f.siteCode())) {
            return blocked(f, "APPROVAL_SITE_OUT_OF_SCOPE",
                    "Site " + f.siteCode() + " is outside the approved site scope.", proto);
        }
        if (!ap.coversProtocol(proto.version())) {
            return blocked(f, "APPROVAL_VERSION_OUT_OF_SCOPE",
                    "Approved envelope does not cover protocol " + proto.version() + ".", proto);
        }
        if (!ap.windowContains(f.event().getOccurredAt())) {
            return blocked(f, "APPROVAL_WINDOW_EXCLUDED",
                    "Occurrence falls outside the approved observation window.", proto);
        }
        if (f.plane() == PlaneType.GLOBAL) {
            if (!ap.isCrossBorder()) {
                return blocked(f, "APPROVAL_NO_CROSSBORDER",
                        "Envelope is local-only; no cross-border transfer is approved.", proto);
            }
            if (!ap.destinationRegionSet().contains("GLOBAL")
                    && !ap.destinationRegionSet().contains(f.destinationRegion().toUpperCase())) {
                return blocked(f, "APPROVAL_DESTINATION_EXCLUDED",
                        "Destination " + f.destinationRegion() + " is not among the approved destinations.", proto);
            }
        }

        // ---------- 3. consent (purpose x fields x region x effective interval) ----------
        ConsentResolution consent = resolveConsent(f, eventFields, protocolFields);
        if (consent.hardBlock) {
            return result(Eligibility.BLOCKED, Verdict.NOT_ELIGIBLE, f.plane(),
                    consent.codes, consent.notes, proto.version(), consent.fields, false);
        }

        // ---------- 4. intersection of field scopes ----------
        Set<String> projected = new LinkedHashSet<>(eventFields);
        projected.retainAll(ap.fieldGroupSet().isEmpty() ? eventFields : ap.fieldGroupSet());
        projected.retainAll(protocolFields.isEmpty() ? eventFields : protocolFields);
        if (!consent.fields.isEmpty()) {
            projected.retainAll(consent.fields);
        }
        if (projected.isEmpty()) {
            return result(Eligibility.BLOCKED, Verdict.NOT_ELIGIBLE, f.plane(),
                    List.of("NO_PROJECTABLE_FIELDS", "APPROVAL_FIELD_OUT_OF_SCOPE"),
                    List.of("Field intersection between event, consent envelope and approved fields is empty."),
                    proto.version(), Set.of() , false);
        }

        // ---------- 5. plane-specific watermark ----------
        Watermark wm = f.watermark();
        if (f.plane() == PlaneType.GLOBAL) {
            if (wm == null) {
                return result(Eligibility.PENDING, Verdict.UNDETERMINED, PlaneType.GLOBAL,
                        List.of("WATERMARK_MISSING"),
                        List.of("No watermark registered for this site/domain; completeness cannot be proven."),
                        proto.version(), projected, true);
            }
            if (f.event().getSourceSeq() > wm.getAnalysisSeq()) {
                return result(Eligibility.PENDING, Verdict.UNDETERMINED, PlaneType.GLOBAL,
                        List.of("WATERMARK_UNCONFIRMED"),
                        List.of("Source sequence " + f.event().getSourceSeq() + " is above the global analysis watermark "
                                + wm.getAnalysisSeq() + " for " + f.siteCode() + "/" + f.event().getDomain()
                                + "; this record is UNCONFIRMED, not absent."),
                        proto.version(), projected, true);
            }
            if (f.event().getSourceSeq() > wm.getTransferAckSeq()) {
                return result(Eligibility.PENDING, Verdict.UNDETERMINED, PlaneType.GLOBAL,
                        List.of("WATERMARK_INCONSISTENCY"),
                        List.of("Record is folded into analysis but its transfer was never acknowledged; quarantined for review."),
                        proto.version(), projected, true);
            }
        } else {
            if (wm != null && f.event().getSourceSeq() > wm.getSourceConfirmedSeq()) {
                return result(Eligibility.PENDING, Verdict.UNDETERMINED, PlaneType.LOCAL,
                        List.of("WATERMARK_UNCONFIRMED"),
                        List.of("Record is above the source-confirmed watermark and is not yet durable at source."),
                        proto.version(), projected, true);
            }
        }

        reasons.addAll(consent.codes);
        reasons.add("APPROVAL_" + ap.getCode());
        reasons.add("FIELDS_" + String.join("+", new TreeSet<>(projected)));
        reasons.addAll(codes);
        notes.addAll(consent.notes);

        if (f.plane() == PlaneType.GLOBAL) {
            return result(Eligibility.GLOBAL_AVAILABLE, Verdict.ELIGIBLE, PlaneType.GLOBAL,
                    new ArrayList<>(reasons), notes, proto.version(), projected, false);
        }
        return result(Eligibility.LOCAL_AVAILABLE, Verdict.ELIGIBLE, PlaneType.LOCAL,
                new ArrayList<>(reasons), notes, proto.version(), projected, false);
    }

    // ------------------------------------------------------------------
    private static EvalResult blocked(Facts f, String code, String note, ProtocolResolution proto) {
        return result(Eligibility.BLOCKED, Verdict.NOT_ELIGIBLE, f.plane(),
                List.of(code), List.of(note), proto == null ? null : proto.version(), Set.of(), false);
    }

    private static EvalResult result(Eligibility eligibility, Verdict verdict, PlaneType plane,
                                     List<String> codes, List<String> notes, String version,
                                     Set<String> fields, boolean gap) {
        return new EvalResult(eligibility, verdict, plane, List.copyOf(codes), List.copyOf(notes),
                version, fields == null ? Set.of() : new TreeSet<>(fields), gap);
    }

    private static String suffix(String s) {
        return s == null || s.isBlank() ? "" : " (" + s + ")";
    }

    private static Set<String> upperCsv(String csv) {
        Set<String> out = new TreeSet<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }
        for (String t : csv.split(",")) {
            if (!t.isBlank()) {
                out.add(t.trim().toUpperCase());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    private record ProtocolResolution(boolean pending, boolean blocked, String version,
                                      Set<String> fieldGroups, String code, String note) {
        List<String> codeList() {
            return List.of(code);
        }

        List<String> noteList() {
            return List.of(note);
        }
    }

    /**
     * A version governs a record only when it is fully approved at the analysis
     * instant, its effective date is at or before the occurrence, it applies to
     * the site, and the record's fields are within its scope. Approval alone is
     * never enough: the explicit effective instant is what moves a record from
     * the previous version to the new one.
     */
    private static ProtocolResolution resolveProtocol(Facts f, Set<String> eventFields) {
        List<com.ctrial.model.ProtocolVersion> candidates = new ArrayList<>();
        for (com.ctrial.model.ProtocolVersion v : f.protocolVersions()) {
            if (!v.appliesToSite(f.siteCode())) {
                continue;
            }
            Set<String> scope = v.requiredFieldGroupSet();
            boolean fieldInScope = scope.isEmpty() || scope.stream().anyMatch(eventFields::contains);
            if (!fieldInScope) {
                continue;
            }
            candidates.add(v);
        }
        Instant occurred = f.event().getOccurredAt();
        List<com.ctrial.model.ProtocolVersion> effective = candidates.stream()
                .filter(v -> v.approvedAt(f.analysisAt()) && !v.getEffectiveFrom().isAfter(occurred))
                .sorted(Comparator.comparing(com.ctrial.model.ProtocolVersion::getEffectiveFrom).reversed())
                .toList();
        if (!effective.isEmpty()) {
            com.ctrial.model.ProtocolVersion chosen = effective.get(0);
            return new ProtocolResolution(false, false, chosen.getVersion(),
                    chosen.requiredFieldGroupSet(), null, null);
        }
        // A version whose explicit effective instant has already arrived but which
        // lacks full approval blocks: it would govern, but cannot yet. A version
        // effective only in the future cannot govern this occurrence and is ignored.
        List<com.ctrial.model.ProtocolVersion> notApproved = candidates.stream()
                .filter(v -> !v.approvedAt(f.analysisAt()))
                .filter(v -> !v.getEffectiveFrom().isAfter(occurred))
                .sorted(Comparator.comparing(com.ctrial.model.ProtocolVersion::getEffectiveFrom).reversed())
                .toList();
        if (!notApproved.isEmpty()) {
            com.ctrial.model.ProtocolVersion v = notApproved.get(0);
            return new ProtocolResolution(true, false, v.getVersion(), v.requiredFieldGroupSet(),
                    "PROTOCOL_APPROVAL_PENDING",
                    "Protocol " + v.getVersion() + " would govern this record but is not yet approved by both ethics "
                            + "and sponsor; the record stays pending instead of reverting to an earlier version.");
        }
        // no effective version covers the fields; maybe only a future amendment does
        List<com.ctrial.model.ProtocolVersion> future = candidates.stream()
                .filter(v -> v.approvedAt(f.analysisAt()) && v.getEffectiveFrom().isAfter(occurred))
                .sorted(Comparator.comparing(com.ctrial.model.ProtocolVersion::getEffectiveFrom))
                .toList();
        if (!future.isEmpty()) {
            return new ProtocolResolution(true, false, future.get(0).getVersion(),
                    future.get(0).requiredFieldGroupSet(),
                    "PROTOCOL_NOT_EFFECTIVE_AT_EVENT",
                    "The only version declaring these fields is effective from "
                            + future.get(0).getEffectiveFrom() + ", after this record occurred; "
                            + "amendments apply only from their explicit effective date to subsequent visits.");
        }
        return new ProtocolResolution(false, true, null, Set.of(),
                "PROTOCOL_FIELD_NOT_IN_SCOPE",
                "No approved protocol version in scope for site " + f.siteCode()
                        + " declares the record's fields (" + String.join(",", eventFields) + ").");
    }

    // ------------------------------------------------------------------
    private static final class ConsentResolution {
        boolean hardBlock;
        boolean pending;
        final List<String> codes = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        Set<String> fields = new TreeSet<>();
    }

    /**
     * Consent is per subject, per purpose, per field group, per region.
     * <ul>
     *   <li>Validity uses the <b>local signed/withdrawn instant</b>, never the
     *       platform receipt instant.</li>
     *   <li>A grant must cover the record's occurrence instant.</li>
     *   <li>A withdrawal stops <b>future queries</b> from its effective
     *       instant. A record that occurred <em>before</em> the withdrawal keeps
     *       a restricted-retention trace (held at occurrence) but is still
     *       blocked from new global queries; a record that occurred
     *       <em>after</em> the withdrawal has no basis at all. The two are
     *       reported with different reason codes.</li>
     * </ul>
     */
    private static ConsentResolution resolveConsent(Facts f, Set<String> eventFields, Set<String> protocolFields) {
        ConsentResolution r = new ConsentResolution();
        Purpose purpose = f.queryPurpose();
        Instant occurred = f.event().getOccurredAt();
        Instant analysis = f.analysisAt();
        String region = f.plane() == PlaneType.GLOBAL ? f.destinationRegion() : f.siteRegion();

        boolean anyGrantForPurpose = false;
        boolean grantCoveredOccurrence = false;
        boolean grantCoveredField = false;
        boolean grantCoveredRegion = false;
        Set<String> grantedFields = new TreeSet<>();

        for (com.ctrial.model.ConsentEntry c : f.consentEntries()) {
            if (c.getKind() != com.ctrial.model.Enums.ConsentKind.GRANT) {
                continue;
            }
            if (!c.purposeSet().contains(Purpose.ALL) && !c.purposeSet().contains(purpose)) {
                continue;
            }
            anyGrantForPurpose = true;
            boolean within = !c.getEffectiveFrom().isAfter(occurred)
                    && (c.getEffectiveTo() == null || c.getEffectiveTo().isAfter(occurred));
            if (!within) {
                continue;
            }
            grantCoveredOccurrence = true;
            if (c.regionSet().stream().anyMatch(reg -> reg.covers(region))) {
                grantCoveredRegion = true;
            }
            Set<String> scope = new TreeSet<>(eventFields);
            scope.retainAll(protocolFields.isEmpty() ? eventFields : protocolFields);
            Set<String> cg = new TreeSet<>(c.fieldGroupSet());
            if (!cg.isEmpty()) {
                scope.retainAll(cg);
            }
            if (!scope.isEmpty()) {
                grantCoveredField = true;
                grantedFields.addAll(scope);
            }
        }

        if (!anyGrantForPurpose) {
            r.hardBlock = true;
            r.codes.add("CONSENT_NO_GRANT_FOR_PURPOSE");
            r.notes.add("No consent entry grants purpose " + purpose + " for this subject.");
            if (f.plane() == PlaneType.GLOBAL && f.chinaResident()) {
                r.codes.add("CHINA_RESIDENCY_DEFAULT_LOCAL");
                r.notes.add("Source record is China-resident and defaults to local residence; no global grant exists.");
            }
            return r;
        }
        if (!grantCoveredOccurrence) {
            r.hardBlock = true;
            r.codes.add("CONSENT_NOT_EFFECTIVE_AT_OCCURRENCE");
            r.notes.add("No grant covering purpose " + purpose + " was effective at the record's occurrence instant "
                    + occurred + " (validity uses the local signed time, not receipt time).");
            return r;
        }
        if (!grantCoveredField) {
            r.hardBlock = true;
            r.codes.add("CONSENT_FIELD_EXCLUDED");
            r.notes.add("The consent grant does not include any field group present in this record.");
            return r;
        }
        r.fields = grantedFields;

        // ---- withdrawal adjudication (earliest applicable withdrawal wins) ----
        com.ctrial.model.ConsentEntry applicableWithdrawal = null;
        for (com.ctrial.model.ConsentEntry c : f.consentEntries()) {
            if (c.getKind() != com.ctrial.model.Enums.ConsentKind.WITHDRAWAL) {
                continue;
            }
            if (c.getEffectiveFrom().isAfter(analysis)) {
                continue;
            }
            boolean purposeHit = c.purposeSet().isEmpty()
                    || c.purposeSet().stream().anyMatch(p -> p == Purpose.ALL || p == purpose);
            if (!purposeHit) {
                continue;
            }
            boolean regionHit = c.regionSet().isEmpty()
                    || c.regionSet().stream().anyMatch(reg -> reg.covers(region));
            if (!regionHit) {
                continue;
            }
            if (applicableWithdrawal == null
                    || c.getEffectiveFrom().isBefore(applicableWithdrawal.getEffectiveFrom())) {
                applicableWithdrawal = c;
            }
        }
        if (applicableWithdrawal != null) {
            Instant wd = applicableWithdrawal.getEffectiveFrom();
            r.hardBlock = true;
            if (occurred.isBefore(wd)) {
                // record existed while consent was in force -> may be retained on the restricted basis
                r.codes.add("CONSENT_WITHDRAWN_BEFORE_QUERY");
                r.codes.add("CONSENT_HELD_AT_OCCURRENCE");
                if (f.retentionRulePresent()) {
                    r.codes.add("RETENTION_RESTRICTED_BASIS");
                }
                r.notes.add("Global purpose was withdrawn effective " + wd
                        + " (local signature time), before this query. The record occurred while consent was in "
                        + "force and may be retained under the pre-registered restricted basis, but it must not "
                        + "enter new global queries.");
            } else {
                r.codes.add("CONSENT_WITHDRAWN_BEFORE_OCCURRENCE");
                r.codes.add("CONSENT_NO_RETENTION_BASIS");
                r.notes.add("Consent for purpose " + purpose + " was withdrawn effective " + wd
                        + ", before this record occurred at " + occurred + "; there is no basis for any use.");
            }
            if (f.plane() == PlaneType.GLOBAL && f.chinaResident()) {
                r.codes.add("CHINA_RESIDENCY_DEFAULT_LOCAL");
            }
            return r;
        }

        if (!grantCoveredRegion) {
            r.hardBlock = true;
            r.codes.add("CONSENT_REGION_EXCLUDED");
            r.notes.add("Consent was granted only for region(s) other than " + region
                    + "; this record cannot leave its approved region.");
            if (f.plane() == PlaneType.GLOBAL && f.chinaResident()) {
                r.codes.add("CHINA_RESIDENCY_DEFAULT_LOCAL");
                r.notes.add("China-resident source records default to local residence.");
            }
            return r;
        }

        r.codes.add("CONSENT_OK");
        return r;
    }
}
