package com.ctrial.repo;

import com.ctrial.model.EndpointAssessment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EndpointAssessmentRepo extends JpaRepository<EndpointAssessment, Long> {
    List<EndpointAssessment> findBySubjectPseudonymOrderByStudyWeekAscOccurredAtAsc(String pseudonym);
    List<EndpointAssessment> findBySiteCodeOrderBySubjectPseudonymAscStudyWeekAsc(String siteCode);
    List<EndpointAssessment> findAllByOrderBySubjectPseudonymAscStudyWeekAsc();
}
