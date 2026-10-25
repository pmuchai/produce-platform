package com.primeroot.platform.sales;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaleLineRepository extends JpaRepository<SaleLine, UUID> {
	List<SaleLine> findBySaleIdOrderByLineNumber(UUID saleId);
}
