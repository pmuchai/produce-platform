package com.primeroot.platform.inventory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockThresholdRepository extends JpaRepository<StockThreshold, UUID> {
	Optional<StockThreshold> findByBranchIdAndProductId(UUID branchId, UUID productId);
	List<StockThreshold> findByBranchIdIn(List<UUID> branches);
}
