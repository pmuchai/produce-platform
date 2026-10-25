package com.primeroot.platform.access;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

public interface BranchRepository extends JpaRepository<Branch, UUID> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select b from Branch b where b.id = :id")
	java.util.Optional<Branch> lockForStockUpdate(UUID id);

	List<Branch> findAllByOrderByCode();

	@Query("select b from StaffUser u join u.branches b where u.id = :userId and b.active = true order by b.code")
	List<Branch> findAccessibleBranches(UUID userId);

	boolean existsByCode(String code);

	boolean existsByCodeAndIdNot(String code, UUID id);
}
