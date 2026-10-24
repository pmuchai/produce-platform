package com.primeroot.platform.audit;

import java.util.List;
import java.util.UUID;

import org.springframework.data.repository.Repository;

public interface AuditRepository extends Repository<AuditEvent, UUID> {
	AuditEvent save(AuditEvent event);

	List<AuditEvent> findTop100ByOrderByOccurredAtDesc();
}
