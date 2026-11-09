package com.primeroot.platform.sales;

import java.util.List;
import java.util.UUID;
import java.time.LocalDate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SaleRepository extends JpaRepository<Sale, UUID> {
	@Query("select s.branch.id from Sale s where s.id = :id")
	java.util.Optional<UUID> branchId(UUID id);

	boolean existsByPaymentMethodAndPaymentReference(Sale.PaymentMethod method, String reference);

	@Query("select distinct s.customer from Sale s where s.branch.id = :branch and s.customer <> 'Walk-ins' order by s.customer")
	List<String> customers(UUID branch, org.springframework.data.domain.Pageable pageable);

	@Query(value = """
		select s from Sale s join fetch s.branch
		where s.branch.id in :branches and s.saleDate between :start and :end
		order by s.saleDate desc, s.id
		""", countQuery = """
		select count(s) from Sale s
		where s.branch.id in :branches and s.saleDate between :start and :end
		""")
	Page<Sale> forEditing(List<UUID> branches, LocalDate start, LocalDate end, Pageable pageable);
}
