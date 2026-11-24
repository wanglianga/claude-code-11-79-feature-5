package com.ctrial.repo;

import com.ctrial.model.ComparisonRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ComparisonRunRepo extends JpaRepository<ComparisonRun, Long> {
    Optional<ComparisonRun> findByCode(String code);
    List<ComparisonRun> findAllByOrderByCreatedAtDesc();
    List<ComparisonRun> findBySiteCodeOrderByCreatedAtDesc(String siteCode);
}
