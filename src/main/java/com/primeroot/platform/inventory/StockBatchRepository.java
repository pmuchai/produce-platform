package com.primeroot.platform.inventory;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockBatchRepository extends JpaRepository<StockBatch, UUID> {
	@Query("""
		select b from StockBatch b where b.branch.id = :branch and b.product.id = :product
		and b.quantity > 0 and (b.expiryDate is null or b.expiryDate >= :today)
		order by case when b.expiryDate is null then 1 else 0 end, b.expiryDate,
			b.receivedDate, b.createdAt, b.id
		""")
	List<StockBatch> saleCandidates(@Param("branch") UUID branch, @Param("product") UUID product,
			@Param("today") LocalDate today, Pageable pageable);

	@Query("""
		select b from StockBatch b where b.branch.id = :branch and b.product.id = :product
		and b.quantity > 0 and b.receivedDate <= :saleDate
		and (b.expiryDate is null or b.expiryDate >= :saleDate)
		and (b.sourceBatch is null or b.operation.postedAt < :until)
		order by case when b.expiryDate is null then 1 else 0 end, b.expiryDate,
			b.receivedDate, b.createdAt, b.id
		""")
	List<StockBatch> saleCandidatesOnDate(UUID branch, UUID product, LocalDate saleDate, Instant until, Pageable pageable);

	@Query("""
		select b.branch.id, b.product.id, sum(b.quantity) from StockBatch b
		where b.branch.id in :branches group by b.branch.id, b.product.id
		""")
	List<Object[]> currentBalances(@Param("branches") List<UUID> branches);

	@Query("""
		select count(b) from StockBatch b where b.branch.id in :branches
		and b.quantity > 0 and b.expiryDate is not null and b.expiryDate <= :through
		""")
	long nearExpiryCount(@Param("branches") List<UUID> branches, @Param("through") LocalDate through);

	@Query("""
		select count(t) from StockThreshold t where t.branch.id in :branches
		and t.minimumQuantity >= coalesce((select sum(b.quantity) from StockBatch b
			where b.branch.id = t.branch.id and b.product.id = t.product.id), 0)
		""")
	long lowStockCount(@Param("branches") List<UUID> branches);

	@Query("""
		select b from StockBatch b where b.branch.id in :branches
		and (lower(b.lotCode) like :search or lower(b.product.name) like :search or lower(b.gradeSnapshot) like :search)
		order by b.receivedDate desc, b.createdAt desc, b.id
		""")
	Page<StockBatch> search(@Param("branches") List<UUID> branches, @Param("search") String search, Pageable pageable);
}
