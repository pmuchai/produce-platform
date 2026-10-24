package com.primeroot.platform.access;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StaffUserRepository extends JpaRepository<StaffUser, UUID> {
	Optional<StaffUser> findByIssuerAndSubject(String issuer, String subject);

	boolean existsByUsername(String username);

	boolean existsByIssuerAndSubject(String issuer, String subject);

	@EntityGraph(attributePaths = "branches")
	List<StaffUser> findAllByOrderByUsername();

}
