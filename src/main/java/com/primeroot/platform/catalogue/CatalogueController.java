package com.primeroot.platform.catalogue;

import java.net.URI;
import java.util.UUID;

import com.primeroot.platform.catalogue.CatalogueDtos.ClassificationRequest;
import com.primeroot.platform.catalogue.CatalogueDtos.ClassificationResponse;
import com.primeroot.platform.catalogue.CatalogueDtos.ConversionResponse;
import com.primeroot.platform.catalogue.CatalogueDtos.ConvertRequest;
import com.primeroot.platform.catalogue.CatalogueDtos.PackagingRequest;
import com.primeroot.platform.catalogue.CatalogueDtos.PackagingResponse;
import com.primeroot.platform.catalogue.CatalogueDtos.ProductRequest;
import com.primeroot.platform.catalogue.CatalogueDtos.ProductResponse;
import com.primeroot.platform.catalogue.CatalogueDtos.Snapshot;
import com.primeroot.platform.catalogue.CatalogueDtos.TypeRequest;
import com.primeroot.platform.catalogue.CatalogueDtos.TypeResponse;
import com.primeroot.platform.catalogue.CatalogueDtos.UnitRequest;
import com.primeroot.platform.catalogue.CatalogueDtos.UnitResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/catalogue")
public class CatalogueController {
	private final CatalogueService catalogue;

	public CatalogueController(CatalogueService catalogue) {
		this.catalogue = catalogue;
	}

	@GetMapping
	public Snapshot list() { return catalogue.list(); }

	@PostMapping("/types")
	public ResponseEntity<TypeResponse> createType(@Valid @RequestBody TypeRequest request) {
		return created(catalogue.saveType(null, request));
	}

	@PutMapping("/types/{id}")
	public TypeResponse updateType(@PathVariable UUID id, @Valid @RequestBody TypeRequest request) {
		return catalogue.saveType(id, request);
	}

	@PostMapping("/classifications")
	public ResponseEntity<ClassificationResponse> createClassification(@Valid @RequestBody ClassificationRequest request) {
		return created(catalogue.saveClassification(null, request));
	}

	@PutMapping("/classifications/{id}")
	public ClassificationResponse updateClassification(@PathVariable UUID id, @Valid @RequestBody ClassificationRequest request) {
		return catalogue.saveClassification(id, request);
	}

	@PostMapping("/units")
	public ResponseEntity<UnitResponse> createUnit(@Valid @RequestBody UnitRequest request) {
		return created(catalogue.saveUnit(null, request));
	}

	@PutMapping("/units/{id}")
	public UnitResponse updateUnit(@PathVariable UUID id, @Valid @RequestBody UnitRequest request) {
		return catalogue.saveUnit(id, request);
	}

	@PostMapping("/products")
	public ResponseEntity<ProductResponse> createProduct(@Valid @RequestBody ProductRequest request) {
		return created(catalogue.saveProduct(null, request));
	}

	@PutMapping("/products/{id}")
	public ProductResponse updateProduct(@PathVariable UUID id, @Valid @RequestBody ProductRequest request) {
		return catalogue.saveProduct(id, request);
	}

	@PutMapping("/packaging")
	public PackagingResponse packaging(@Valid @RequestBody PackagingRequest request) {
		return catalogue.savePackaging(request);
	}

	@PostMapping("/convert")
	public ConversionResponse convert(@Valid @RequestBody ConvertRequest request) {
		return catalogue.convert(request);
	}

	private static <T> ResponseEntity<T> created(T body) {
		return ResponseEntity.created(URI.create("/api/catalogue")).body(body);
	}
}
