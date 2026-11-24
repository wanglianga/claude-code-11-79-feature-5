package com.ctrial.repo;

import com.ctrial.model.AnalysisDefinition;
import com.ctrial.model.Enums.DefinitionKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AnalysisDefinitionRepo extends JpaRepository<AnalysisDefinition, Long> {
    Optional<AnalysisDefinition> findByCode(String code);
    Optional<AnalysisDefinition> findByDefinitionKey(DefinitionKey key);
    List<AnalysisDefinition> findAllByOrderByApplicableFromAsc();
}
