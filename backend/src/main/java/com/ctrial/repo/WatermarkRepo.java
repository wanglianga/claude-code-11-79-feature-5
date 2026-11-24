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

public interface WatermarkRepo extends JpaRepository<Watermark, Long> {
        Optional<Watermark> findBySiteCodeAndDomain(String siteCode, Enums.Domain domain);
        List<Watermark> findBySiteCodeOrderByDomainAsc(String siteCode);
        List<Watermark> findAllByOrderBySiteCodeAscDomainAsc();
}
