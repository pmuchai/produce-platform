package com.primeroot.platform.audit;

import java.util.List;
import java.util.UUID;
import java.time.Instant;

import org.springframework.data.repository.Repository;

public interface AuditRepository extends Repository<AuditEvent, UUID> {
	AuditEvent save(AuditEvent event);

	List<AuditEvent> findTop100ByOrderByOccurredAtDesc();
	long countByOccurredAtGreaterThanEqualAndOccurredAtLessThan(Instant start, Instant end);
	List<AuditEvent> findTop100ByOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtDesc(
		Instant start, Instant end);
}
