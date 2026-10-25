package com.primeroot.platform.catalogue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, UUID> {
	List<Product> findAllByOrderByCode();
	boolean existsByProduceTypeIdAndActiveTrue(UUID id);
	boolean existsByVarietyIdOrGradeId(UUID varietyId, UUID gradeId);
	boolean existsByVarietyIdAndActiveTrueOrGradeIdAndActiveTrue(UUID varietyId, UUID gradeId);
	boolean existsByBaseUnitIdAndActiveTrue(UUID id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select item from Product item where item.id = :id")
	Optional<Product> findByIdForUpdate(@Param("id") UUID id);
}
