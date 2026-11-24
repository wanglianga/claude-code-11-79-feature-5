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

public interface EligibilityRowRepo extends JpaRepository<EligibilityRow, Long> {
        List<EligibilityRow> findByRunIdOrderByLocalDayAscSubjectPseudonymAsc(Long runId);
        List<EligibilityRow> findByRunIdAndPlaneOrderByOccurredAtAsc(Long runId, Enums.PlaneType plane);
        List<EligibilityRow> findByRunIdAndSubjectPseudonym(Long runId, String subjectPseudonym);

        @Transactional
        @Modifying
        @Query("delete from EligibilityRow r where r.run.id = :runId")
        int deleteByRunId(@Param("runId") Long runId);
}
