package com.primeroot.platform.sales;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SaleRepository extends JpaRepository<Sale, UUID> {
	@Query("select s.branch.id from Sale s where s.id = :id")
	java.util.Optional<UUID> branchId(UUID id);

	boolean existsByPaymentMethodAndPaymentReference(Sale.PaymentMethod method, String reference);

	@Query("select distinct s.customer from Sale s where s.branch.id = :branch and s.customer <> 'Walk-ins' order by s.customer")
	List<String> customers(UUID branch, org.springframework.data.domain.Pageable pageable);
}
