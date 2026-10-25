package com.primeroot.platform.sales;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SaleAllocationRepository extends JpaRepository<SaleAllocation, UUID> {
	@Query("select a from SaleAllocation a join fetch a.line join fetch a.movement m join fetch m.batch where a.line.sale.id = :sale order by a.line.lineNumber, m.id")
	List<SaleAllocation> forSale(UUID sale);
}
