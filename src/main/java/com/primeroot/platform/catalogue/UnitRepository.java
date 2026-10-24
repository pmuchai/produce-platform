package com.primeroot.platform.catalogue;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UnitRepository extends JpaRepository<UnitOfMeasure, UUID> {
	List<UnitOfMeasure> findAllByOrderByCode();
}
