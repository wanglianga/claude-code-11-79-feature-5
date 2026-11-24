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

public interface QuarantineRepo extends JpaRepository<QuarantineItem, Long> {
        Optional<QuarantineItem> findByCode(String code);
        List<QuarantineItem> findAllByOrderByOpenedAtDesc();
        List<QuarantineItem> findByStateOrderByOpenedAtDesc(Enums.QuarantineState state);
}
