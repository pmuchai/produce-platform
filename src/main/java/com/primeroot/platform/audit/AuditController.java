package com.primeroot.platform.audit;

import java.util.List;

import com.primeroot.platform.audit.AuditService.AuditResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuditController {
	private final AuditService audit;

	public AuditController(AuditService audit) {
		this.audit = audit;
	}

	@GetMapping("/api/audit-events")
	public List<AuditResponse> list() {
		return audit.list();
	}
}
