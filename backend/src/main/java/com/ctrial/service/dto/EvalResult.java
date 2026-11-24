package com.ctrial.service.dto;

import com.ctrial.model.Enums.Eligibility;
import com.ctrial.model.Enums.PlaneType;
import com.ctrial.model.Enums.Verdict;

import java.util.List;
import java.util.Set;

/** Result of one rule-engine evaluation. Field order is stable for hashing. */
public record EvalResult(
        Eligibility eligibility,
        Verdict verdict,
        PlaneType plane,
        List<String> reasonCodes,
        List<String> reasons,
        String protocolVersion,
        Set<String> projectedFields,
        boolean watermarkGap
) {
    public String reasonCodeCsv() {
        return String.join(",", reasonCodes.stream().distinct().sorted().toList());
    }

    public String reasonText() {
        return String.join(" ", reasons);
    }

    public String projectedCsv() {
        return String.join(",", projectedFields);
    }

    public boolean eligible() {
        return eligibility == Eligibility.LOCAL_AVAILABLE || eligibility == Eligibility.GLOBAL_AVAILABLE;
    }

    /** Canonical string used for digests. Never includes wall-clock time. */
    public String canonical() {
        return eligibility + "|" + verdict + "|" + plane + "|" + reasonCodeCsv() + "|"
                + (protocolVersion == null ? "" : protocolVersion) + "|" + projectedCsv();
    }
}
