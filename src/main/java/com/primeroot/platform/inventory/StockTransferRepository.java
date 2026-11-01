package com.primeroot.platform.inventory;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StockTransferRepository extends JpaRepository<StockTransfer, UUID> {
	@Query("""
		select t from StockTransfer t, StockOperation o where o.id = t.id
		and (t.source.id in :branches or t.destination.id in :branches)
		and (:status is null or t.status = :status)
		order by o.postedAt desc, t.id
		""")
	Page<StockTransfer> history(List<UUID> branches, StockTransfer.Status status, Pageable pageable);
}
