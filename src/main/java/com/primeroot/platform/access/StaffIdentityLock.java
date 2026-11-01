package com.primeroot.platform.access;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StaffIdentityLock {
	private final JdbcTemplate jdbc;

	public StaffIdentityLock(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void lock() {
		jdbc.queryForObject("SELECT lock_key FROM staff_identity_lock WHERE lock_key = 1 FOR UPDATE", Integer.class);
	}
}
