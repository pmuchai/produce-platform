package com.primeroot.platform.inventory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StockMovementRepository extends JpaRepository<StockMovement, UUID> {
	List<StockMovement> findByOperationIdOrderById(UUID operationId);
	boolean existsByReversalOfId(UUID id);

	@Query("""
		select m from StockMovement m where m.operation.branch.id in :branches
		and m.operation.postedAt >= :from and m.operation.postedAt < :until
		order by m.operation.postedAt desc, m.id
		""")
	Page<StockMovement> history(List<UUID> branches, Instant from, Instant until, Pageable pageable);

	@Query("""
		select m.batch.branch.id, m.batch.product.id, sum(m.quantity)
		from StockMovement m where m.operation.branch.id in :branches and m.operation.postedAt < :until
		group by m.batch.branch.id, m.batch.product.id
		""")
	List<Object[]> balances(List<UUID> branches, Instant until);

	@Query("""
		select m.batch.branch.id, m.batch.product.id, m.kind, sum(m.quantity)
		from StockMovement m where m.operation.branch.id in :branches
		and m.operation.postedAt >= :from and m.operation.postedAt < :until
		group by m.batch.branch.id, m.batch.product.id, m.kind
		""")
	List<Object[]> totals(List<UUID> branches, Instant from, Instant until);
}
