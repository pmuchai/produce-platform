package com.primeroot.platform.finance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.primeroot.platform.sales.Sale;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ExpenseRepository extends JpaRepository<Expense, UUID> {
	Optional<Expense> findByVoidRequestId(UUID id);
	boolean existsByPaymentMethodAndPaymentReference(Sale.PaymentMethod method, String reference);

	@Query("""
		select e from Expense e where e.branch.id in :branches
		and (e.paidDate between :start and :end or (e.voidedAt >= :from and e.voidedAt < :until))
		and (:category is null or e.category = :category)
		order by e.paidDate desc, e.postedAt desc, e.id
		""")
	Page<Expense> history(List<UUID> branches, LocalDate start, LocalDate end, Instant from, Instant until,
		Expense.Category category, Pageable pageable);
}
