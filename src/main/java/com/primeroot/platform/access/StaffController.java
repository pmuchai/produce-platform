package com.primeroot.platform.access;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.primeroot.platform.access.AccessDtos.CreateUserRequest;
import com.primeroot.platform.access.AccessDtos.AccessRequestResponse;
import com.primeroot.platform.access.AccessDtos.ApproveAccessRequest;
import com.primeroot.platform.access.AccessDtos.UpdateUserRequest;
import com.primeroot.platform.access.AccessDtos.UserResponse;
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
@RequestMapping("/api/users")
public class StaffController {
	private final StaffService users;

	public StaffController(StaffService users) {
		this.users = users;
	}

	@GetMapping
	public List<UserResponse> list() {
		return users.list();
	}

	@GetMapping("/access-requests")
	public List<AccessRequestResponse> pendingRequests() {
		return users.pendingRequests();
	}

	@PostMapping("/access-requests/{id}/approve")
	public ResponseEntity<UserResponse> approveRequest(@PathVariable UUID id,
			@Valid @RequestBody ApproveAccessRequest request) {
		var user = users.approveRequest(id, request);
		return ResponseEntity.created(URI.create("/api/users/" + user.id()))
			.header(HttpHeaders.ETAG, etag(user.version())).body(user);
	}

	@PostMapping
	public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
		var user = users.create(request);
		return ResponseEntity.created(URI.create("/api/users/" + user.id()))
			.header(HttpHeaders.ETAG, etag(user.version())).body(user);
	}

	@PutMapping("/{id}")
	public ResponseEntity<UserResponse> update(@PathVariable UUID id,
			@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
			@Valid @RequestBody UpdateUserRequest request) {
		var user = users.update(id, request, ifMatch);
		return ResponseEntity.ok().header(HttpHeaders.ETAG, etag(user.version())).body(user);
	}

	private static String etag(long version) {
		return "\"" + version + "\"";
	}
}
