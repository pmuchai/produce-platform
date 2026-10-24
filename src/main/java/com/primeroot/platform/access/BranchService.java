package com.primeroot.platform.access;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.primeroot.platform.access.AccessDtos.BranchRequest;
import com.primeroot.platform.access.AccessDtos.BranchResponse;
import com.primeroot.platform.audit.AuditEvent;
import com.primeroot.platform.audit.AuditRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class BranchService {
	private final BranchRepository branches;
	private final AccessService access;
	private final AuditRepository audit;

	public BranchService(BranchRepository branches, AccessService access, AuditRepository audit) {
		this.branches = branches;
		this.access = access;
		this.audit = audit;
	}

	public List<BranchResponse> list() {
		var user = access.requirePermission(Permission.BRANCH_VIEW);
		var visible = user.isAllBranches()
			? branches.findAllByOrderByCode() : branches.findAccessibleBranches(user.getId());
		return visible.stream().map(BranchResponse::from).toList();
	}

	public BranchResponse get(UUID id) {
		access.requirePermission(Permission.BRANCH_VIEW);
		access.requireBranch(id);
		return BranchResponse.from(find(id));
	}

	@Transactional
	public BranchResponse create(BranchRequest request) {
		var actor = access.requireGlobalPermission(Permission.BRANCH_MANAGE);
		var code = request.code().toUpperCase(Locale.ROOT);
		if (branches.existsByCode(code)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Branch code already exists");
		}
		var branch = new Branch(code, request.name(), request.address(), request.active());
		branches.saveAndFlush(branch);
		audit.save(new AuditEvent(actor.getId(), "BRANCH_CREATED", "BRANCH", branch.getId()));
		return BranchResponse.from(branch);
	}

	@Transactional
	public BranchResponse update(UUID id, BranchRequest request, String ifMatch) {
		var actor = access.requirePermission(Permission.BRANCH_MANAGE);
		access.requireBranch(id);
		var branch = find(id);
		requireMatchingVersion(ifMatch, branch.getVersion());
		var code = request.code().toUpperCase(Locale.ROOT);
		if (branches.existsByCodeAndIdNot(code, id)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Branch code already exists");
		}
		branch.update(code, request.name(), request.address(), request.active());
		branches.saveAndFlush(branch);
		audit.save(new AuditEvent(actor.getId(), "BRANCH_UPDATED", "BRANCH", id));
		return BranchResponse.from(branch);
	}

	private static void requireMatchingVersion(String ifMatch, long version) {
		if (ifMatch == null || ifMatch.isBlank()) {
			throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
				"An If-Match header is required");
		}
		if (!ifMatch.equals("\"" + version + "\"")) {
			throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
				"The branch changed; reload it and try again");
		}
	}

	private Branch find(UUID id) {
		return branches.findById(id)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch not found"));
	}
}
