package com.ctrial.repo;

import com.ctrial.model.DifferenceAnnotation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DifferenceAnnotationRepo extends JpaRepository<DifferenceAnnotation, Long> {
    List<DifferenceAnnotation> findByComparisonVersionIdOrderByCreatedAtAsc(Long versionId);
}
