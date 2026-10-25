package com.primeroot.platform.catalogue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UnitRepository extends JpaRepository<UnitOfMeasure, UUID> {
	List<UnitOfMeasure> findAllByOrderByCode();

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select unit from ProductPackaging packaging join packaging.unit unit "
			+ "where packaging.product.id = :productId order by unit.id")
	List<UnitOfMeasure> findPackagingUnitsByProductIdForUpdate(@Param("productId") UUID productId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select item from UnitOfMeasure item where item.id = :id")
	Optional<UnitOfMeasure> findByIdForUpdate(@Param("id") UUID id);
}
