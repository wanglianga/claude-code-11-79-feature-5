package com.ctrial.repo;

import com.ctrial.model.LocalBridgeAggregate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LocalBridgeAggregateRepo extends JpaRepository<LocalBridgeAggregate, Long> {
    List<LocalBridgeAggregate> findByComparisonVersionIdOrderBySiteCodeAscEndpointKindAsc(Long versionId);
    List<LocalBridgeAggregate> findByComparisonRunIdOrderBySiteCodeAscEndpointKindAsc(Long runId);
}
