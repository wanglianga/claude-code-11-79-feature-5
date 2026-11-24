package com.ctrial.repo;

import com.ctrial.model.FrozenMonthlyReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FrozenMonthlyReportRepo extends JpaRepository<FrozenMonthlyReport, Long> {
    Optional<FrozenMonthlyReport> findByCode(String code);
    List<FrozenMonthlyReport> findAllByOrderByFrozenAtDesc();
}
