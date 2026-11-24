package com.ctrial.service;

import com.ctrial.model.Enums.QuarantineKind;
import com.ctrial.model.Enums.QuarantineState;
import com.ctrial.model.LocalEvent;
import com.ctrial.model.QuarantineItem;
import com.ctrial.repo.QuarantineRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class QuarantineService {

    private final QuarantineRepo repo;
    private final AuditService audit;

    public QuarantineService(QuarantineRepo repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
    }

    @Transactional
    public QuarantineItem open(QuarantineKind kind, String stableId, String pseudonym, LocalEvent event, String detail) {
        QuarantineItem q = new QuarantineItem();
        long seq = repo.count() + 1;
        q.setCode("Q-" + String.format("%04d", seq) + "-" + kind.name().substring(0, 3));
        q.setKind(kind);
        q.setState(QuarantineState.OPEN);
        q.setStableId(stableId);
        q.setSubjectPseudonym(pseudonym);
        q.setEvent(event);
        q.setDetail(detail);
        q.setOpenedAt(Instant.now());
        q = repo.save(q);
        audit.record("system:quarantine", "QUARANTINED", "QuarantineItem", q.getCode(), detail);
        return q;
    }

    @Transactional
    public QuarantineItem resolve(String code, String actor, String note) {
        QuarantineItem q = repo.findByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("Quarantine item " + code + " not found"));
        q.setState(QuarantineState.RESOLVED);
        q.setResolvedAt(Instant.now());
        q.setResolutionNote(note);
        repo.save(q);
        audit.record(actor, "QUARANTINE_RESOLVED", "QuarantineItem", code, note);
        return q;
    }

    public List<QuarantineItem> all() {
        return repo.findAllByOrderByOpenedAtDesc();
    }

    public long openCount() {
        return repo.findByStateOrderByOpenedAtDesc(QuarantineState.OPEN).size();
    }
}
