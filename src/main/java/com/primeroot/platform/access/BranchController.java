package com.primeroot.platform.access;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.primeroot.platform.access.AccessDtos.BranchRequest;
import com.primeroot.platform.access.AccessDtos.BranchResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/branches")
public class BranchController {
	private final BranchService branches;

	public BranchController(BranchService branches) {
		this.branches = branches;
	}

	@GetMapping
	public List<BranchResponse> list() {
		return branches.list();
	}

	@GetMapping("/{id}")
	public ResponseEntity<BranchResponse> get(@PathVariable UUID id) {
		var branch = branches.get(id);
		return ResponseEntity.ok().header(HttpHeaders.ETAG, etag(branch.version())).body(branch);
	}

	@PostMapping
	public ResponseEntity<BranchResponse> create(@Valid @RequestBody BranchRequest request) {
		var branch = branches.create(request);
		return ResponseEntity.created(URI.create("/api/branches/" + branch.id()))
			.header(HttpHeaders.ETAG, etag(branch.version())).body(branch);
	}

	@PutMapping("/{id}")
	public ResponseEntity<BranchResponse> update(@PathVariable UUID id,
			@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
			@Valid @RequestBody BranchRequest request) {
		var branch = branches.update(id, request, ifMatch);
		return ResponseEntity.ok().header(HttpHeaders.ETAG, etag(branch.version())).body(branch);
	}

	private static String etag(long version) {
		return "\"" + version + "\"";
	}
}
