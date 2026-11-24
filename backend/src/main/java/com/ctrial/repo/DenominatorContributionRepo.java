package com.ctrial.repo;

import com.ctrial.model.DenominatorContribution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DenominatorContributionRepo extends JpaRepository<DenominatorContribution, Long> {
    List<DenominatorContribution> findByComparisonVersionIdOrderBySiteCodeAscConsentScopeAscRiskStratumAsc(Long versionId);
}
