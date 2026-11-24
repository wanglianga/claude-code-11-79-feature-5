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

public interface EventRepo extends JpaRepository<LocalEvent, Long> {
        List<LocalEvent> findBySubjectIdOrderByOccurredAtAscRevisionAsc(Long subjectId);
        Optional<LocalEvent> findBySourceSystemAndDomainAndSourceSeq(String sourceSystem, Enums.Domain domain, long seq);
        Optional<LocalEvent> findByStableIdAndRevision(String stableId, int revision);
        List<LocalEvent> findByStableIdOrderByRevisionAsc(String stableId);
        List<LocalEvent> findBySubjectIdAndDomainAndVoidedFalseOrderByOccurredAtAsc(Long subjectId, Enums.Domain domain);
        List<LocalEvent> findAllByOrderByOccurredAtAscIdAsc();
        long countBySourceSystem(String sourceSystem);
        long countBySourceSystemAndSourceSeqGreaterThan(String sourceSystem, long seq);
}
