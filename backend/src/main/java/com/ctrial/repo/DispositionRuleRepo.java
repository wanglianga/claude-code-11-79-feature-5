package com.ctrial.repo;

import com.ctrial.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DispositionRuleRepo extends JpaRepository<DispositionRule, Long> {
        Optional<DispositionRule> findByCode(String code);
        List<DispositionRule> findByActiveTrueOrderByCodeAsc();
        List<DispositionRule> findAllByOrderByCodeAsc();
}
