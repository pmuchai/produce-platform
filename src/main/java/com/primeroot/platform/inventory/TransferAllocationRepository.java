package com.primeroot.platform.inventory;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferAllocationRepository extends JpaRepository<TransferAllocation, UUID> {
	List<TransferAllocation> findByTransferIdOrderById(UUID transferId);
}
