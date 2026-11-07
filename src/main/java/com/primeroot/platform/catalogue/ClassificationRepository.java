package com.primeroot.platform.catalogue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClassificationRepository extends JpaRepository<ProductClassification, UUID> {
	List<ProductClassification> findAllByOrderByCode();
	boolean existsByProduceTypeIdAndActiveTrue(UUID id);

	@Query(value = "SELECT COUNT(*) > 0 FROM stock_batches WHERE grade_id = :id", nativeQuery = true)
	boolean existsAsReceivedGrade(@Param("id") UUID id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select item from ProductClassification item where item.id = :id")
	Optional<ProductClassification> findByIdForUpdate(@Param("id") UUID id);
}
