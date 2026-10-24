package com.primeroot.platform.access;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.primeroot.platform.access.AccessDtos.CreateUserRequest;
import com.primeroot.platform.access.AccessDtos.UpdateUserRequest;
import com.primeroot.platform.access.AccessDtos.UserResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

	@PostMapping
	public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
		var user = users.create(request);
		return ResponseEntity.created(URI.create("/api/users/" + user.id())).body(user);
	}

	@PutMapping("/{id}")
	public UserResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
		return users.update(id, request);
	}

}
