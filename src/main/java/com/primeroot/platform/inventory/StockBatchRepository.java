package com.primeroot.platform.inventory;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockBatchRepository extends JpaRepository<StockBatch, UUID> {
	List<StockBatch> findByBranchIdInOrderByReceivedDateAscCreatedAtAsc(List<UUID> branches);
}
