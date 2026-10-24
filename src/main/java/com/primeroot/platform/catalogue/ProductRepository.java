package com.primeroot.platform.catalogue;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, UUID> {
	List<Product> findAllByOrderByCode();
	boolean existsByProduceTypeIdAndActiveTrue(UUID id);
	boolean existsByVarietyIdOrGradeId(UUID varietyId, UUID gradeId);
	boolean existsByVarietyIdAndActiveTrueOrGradeIdAndActiveTrue(UUID varietyId, UUID gradeId);
	boolean existsByBaseUnitIdAndActiveTrue(UUID id);
}
