package com.primeroot.platform.catalogue;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProduceTypeRepository extends JpaRepository<ProduceType, UUID> {
	List<ProduceType> findAllByOrderByCode();
}
