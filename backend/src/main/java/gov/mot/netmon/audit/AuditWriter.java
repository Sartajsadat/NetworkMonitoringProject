package gov.mot.netmon.audit;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Persists audit entries in a new transaction so they survive a rollback of the caller's work. */
@Component
class AuditWriter {

    private final AuditLogRepository repository;

    AuditWriter(AuditLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(AuditLog entry) {
        repository.save(entry);
    }
}
