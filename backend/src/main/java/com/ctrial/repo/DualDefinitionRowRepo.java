package com.ctrial.repo;

import com.ctrial.model.DualDefinitionRow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DualDefinitionRowRepo extends JpaRepository<DualDefinitionRow, Long> {
    List<DualDefinitionRow> findByComparisonRunIdOrderBySiteCodeAscSubjectPseudonymAsc(Long runId);
    Optional<DualDefinitionRow> findByComparisonRunIdAndSubjectPseudonym(Long runId, String pseudonym);
    List<DualDefinitionRow> findByComparisonRunIdAndSiteCodeOrderBySubjectPseudonymAsc(Long runId, String siteCode);
}
