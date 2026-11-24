package com.ctrial.repo;

import com.ctrial.model.ComparisonWatermark;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ComparisonWatermarkRepo extends JpaRepository<ComparisonWatermark, Long> {
    Optional<ComparisonWatermark> findByCode(String code);
    List<ComparisonWatermark> findAllByOrderByCapturedAtDesc();
}
