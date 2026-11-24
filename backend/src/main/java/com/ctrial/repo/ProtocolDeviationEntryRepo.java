package com.ctrial.repo;

import com.ctrial.model.ProtocolDeviationEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProtocolDeviationEntryRepo extends JpaRepository<ProtocolDeviationEntry, Long> {
    List<ProtocolDeviationEntry> findBySubjectPseudonym(String pseudonym);
    List<ProtocolDeviationEntry> findAllByOrderByOccurredAtAsc();
}
