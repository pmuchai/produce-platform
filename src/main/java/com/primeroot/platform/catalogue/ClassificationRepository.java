package com.primeroot.platform.catalogue;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClassificationRepository extends JpaRepository<ProductClassification, UUID> {
	List<ProductClassification> findAllByOrderByCode();
	boolean existsByProduceTypeIdAndActiveTrue(UUID id);
}
