package com.primeroot.platform.access;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StaffAccessRequestRepository extends JpaRepository<StaffAccessRequest, UUID> {
	List<StaffAccessRequest> findByIssuerAndApprovedFalseOrderByCreatedAtAsc(String issuer);

	boolean existsByIssuerAndSubjectAndApprovedFalse(String issuer, String subject);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<StaffAccessRequest> findByIssuerAndSubject(String issuer, String subject);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from StaffAccessRequest r where r.id = :id")
	Optional<StaffAccessRequest> lockById(@Param("id") UUID id);
}
