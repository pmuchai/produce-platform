package com.primeroot.platform.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.primeroot.platform.access.AccessService;
import com.primeroot.platform.access.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AuditService {
	private final AuditRepository audit;
	private final AccessService access;

	public AuditService(AuditRepository audit, AccessService access) {
		this.audit = audit;
		this.access = access;
	}

	public List<AuditResponse> list() {
		access.requireGlobalPermission(Permission.AUDIT_VIEW);
		return audit.findTop100ByOrderByOccurredAtDesc().stream()
			.map(event -> new AuditResponse(event.getId(), event.getActorId(), event.getAction(),
				event.getTargetType(), event.getTargetId(), event.getOccurredAt()))
			.toList();
	}

	public long count(Instant start, Instant endExclusive) {
		access.requireGlobalPermission(Permission.AUDIT_VIEW);
		return audit.countByOccurredAtGreaterThanEqualAndOccurredAtLessThan(start, endExclusive);
	}

	public List<AuditResponse> list(Instant start, Instant endExclusive) {
		access.requireGlobalPermission(Permission.AUDIT_VIEW);
		return audit.findTop100ByOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtDesc(start, endExclusive)
			.stream().map(event -> new AuditResponse(event.getId(), event.getActorId(), event.getAction(),
				event.getTargetType(), event.getTargetId(), event.getOccurredAt())).toList();
	}

	public record AuditResponse(UUID id, UUID actorId, String action, String targetType,
			UUID targetId, Instant occurredAt) {
	}
}
