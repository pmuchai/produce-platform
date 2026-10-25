package com.primeroot.platform.inventory;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockOperationRepository extends JpaRepository<StockOperation, UUID> {
}
