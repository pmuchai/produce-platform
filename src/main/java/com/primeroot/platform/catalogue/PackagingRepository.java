package com.primeroot.platform.catalogue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PackagingRepository extends JpaRepository<ProductPackaging, UUID> {
	List<ProductPackaging> findAllByOrderByProductCodeAscUnitCodeAsc();
	Optional<ProductPackaging> findByProductIdAndUnitId(UUID productId, UUID unitId);
	boolean existsByUnitIdAndProductActiveTrue(UUID id);
}
