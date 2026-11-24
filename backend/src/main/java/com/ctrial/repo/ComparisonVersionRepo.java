package com.ctrial.repo;

import com.ctrial.model.ComparisonVersion;
import com.ctrial.model.Enums.BridgeRoute;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ComparisonVersionRepo extends JpaRepository<ComparisonVersion, Long> {
    Optional<ComparisonVersion> findByCode(String code);
    List<ComparisonVersion> findAllByOrderByPublishedAtDesc();
    List<ComparisonVersion> findByLinkedFrozenReportCodeOrderByPublishedAtDesc(String frozenReportCode);
    Optional<ComparisonVersion> findFirstByLinkedFrozenReportCodeAndRouteOrderByPublishedAtDesc(String frozenReportCode,
                                                                                                BridgeRoute route);
}
