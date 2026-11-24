package com.ctrial.repo;

import com.ctrial.model.LocalEndpointWatermark;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LocalEndpointWatermarkRepo extends JpaRepository<LocalEndpointWatermark, Long> {
    Optional<LocalEndpointWatermark> findBySiteCode(String siteCode);
    List<LocalEndpointWatermark> findAllByOrderBySiteCodeAsc();
}
