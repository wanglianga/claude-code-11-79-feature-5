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

public interface ReceiptRepo extends JpaRepository<TransportReceipt, Long> {
        List<TransportReceipt> findBySourceSystemAndDomainAndSourceSeq(String sourceSystem, String domain, long seq);
        List<TransportReceipt> findBySourceSystemOrderByAckedAtAsc(String sourceSystem);
        List<TransportReceipt> findAllByOrderByAckedAtDesc();
}
