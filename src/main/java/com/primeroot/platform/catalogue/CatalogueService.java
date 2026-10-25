package com.primeroot.platform.catalogue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

import com.primeroot.platform.access.AccessService;
import com.primeroot.platform.access.Permission;
import com.primeroot.platform.audit.AuditEvent;
import com.primeroot.platform.audit.AuditRepository;
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
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class CatalogueService {
	private final ProduceTypeRepository types;
	private final ClassificationRepository classifications;
	private final UnitRepository units;
	private final ProductRepository products;
	private final PackagingRepository packaging;
	private final AccessService access;
	private final AuditRepository audit;

	public CatalogueService(ProduceTypeRepository types, ClassificationRepository classifications,
			UnitRepository units, ProductRepository products, PackagingRepository packaging,
			AccessService access, AuditRepository audit) {
		this.types = types;
		this.classifications = classifications;
		this.units = units;
		this.products = products;
		this.packaging = packaging;
		this.access = access;
		this.audit = audit;
	}

	public Snapshot list() {
		access.requirePermission(Permission.CATALOGUE_VIEW);
		return new Snapshot(types.findAllByOrderByCode().stream().map(TypeResponse::from).toList(),
			classifications.findAllByOrderByCode().stream().map(ClassificationResponse::from).toList(),
			units.findAllByOrderByCode().stream().map(UnitResponse::from).toList(),
			products.findAllByOrderByCode().stream().map(ProductResponse::from).toList(),
			packaging.findAllByOrderByProductCodeAscUnitCodeAsc().stream().map(PackagingResponse::from).toList());
	}

	@Transactional
	public TypeResponse saveType(UUID id, TypeRequest request) {
		var actor = access.requireGlobalPermission(Permission.CATALOGUE_MANAGE);
		var item = id == null ? new ProduceType() : findForUpdate(types, id);
		if (id != null && !request.active() && (classifications.existsByProduceTypeIdAndActiveTrue(id)
				|| products.existsByProduceTypeIdAndActiveTrue(id))) {
			throw conflict("Deactivate dependent varieties, grades and products first");
		}
		item.update(request.code(), request.name(), request.active());
		types.saveAndFlush(item);
		record(actor.getId(), id, "PRODUCE_TYPE", item.getId());
		return TypeResponse.from(item);
	}

	@Transactional
	public ClassificationResponse saveClassification(UUID id, ClassificationRequest request) {
		var actor = access.requireGlobalPermission(Permission.CATALOGUE_MANAGE);
		var type = findForUpdate(types, request.produceTypeId());
		requireActive(request.active(), type);
		var item = id == null ? new ProductClassification() : findForUpdate(classifications, id);
		if (id != null && products.existsByVarietyIdOrGradeId(id, id)
				&& (item.getKind() != request.kind() || !item.getProduceType().getId().equals(type.getId()))) {
			throw conflict("A classification used by products cannot change kind or produce type");
		}
		if (id != null && !request.active()
				&& products.existsByVarietyIdAndActiveTrueOrGradeIdAndActiveTrue(id, id)) {
			throw conflict("Deactivate products using this classification first");
		}
		item.update(request.code(), request.name(), request.active());
		item.classify(type, request.kind());
		classifications.saveAndFlush(item);
		record(actor.getId(), id, "CLASSIFICATION", item.getId());
		return ClassificationResponse.from(item);
	}

	@Transactional
	public UnitResponse saveUnit(UUID id, UnitRequest request) {
		var actor = access.requireGlobalPermission(Permission.CATALOGUE_MANAGE);
		if (request.dimension() == UnitOfMeasure.Dimension.PACKAGING
				? request.baseFactor() != null : request.baseFactor() == null) {
			throw badRequest("Mass/count units require a positive base factor; packaging units must not have one");
		}
		var item = id == null ? new UnitOfMeasure() : findForUpdate(units, id);
		if (id != null && (item.getDimension() != request.dimension()
				|| !equal(item.getBaseFactor(), request.baseFactor()))) {
			throw conflict("Unit dimensions and factors are immutable; create a new unit instead");
		}
		if (id != null && !request.active()
				&& (products.existsByBaseUnitIdAndActiveTrue(id) || packaging.existsByUnitIdAndProductActiveTrue(id))) {
			throw conflict("Deactivate products using this unit first");
		}
		item.update(request.code(), request.name(), request.active());
		item.measure(request.dimension(), request.baseFactor());
		units.saveAndFlush(item);
		record(actor.getId(), id, "UNIT", item.getId());
		return UnitResponse.from(item);
	}

	@Transactional
	public ProductResponse saveProduct(UUID id, ProductRequest request) {
		var actor = access.requireGlobalPermission(Permission.CATALOGUE_MANAGE);
		var type = findForUpdate(types, request.produceTypeId());
		var variety = classification(request.varietyId(), type, ProductClassification.Kind.VARIETY);
		var grade = classification(request.gradeId(), type, ProductClassification.Kind.GRADE);
		var item = id == null ? new Product() : findForUpdate(products, id);
		var unit = findForUpdate(units, request.baseUnitId());
		if (unit.getDimension() == UnitOfMeasure.Dimension.PACKAGING) {
			throw badRequest("A product's base stock unit must measure mass or count, not variable packaging");
		}
		requireActive(request.active(), type, unit, variety, grade);
		if (id != null && !item.getBaseUnit().getId().equals(unit.getId())) {
			throw conflict("A product's base stock unit is immutable; create a new product instead");
		}
		if (request.active() && id != null && packaging.findAllByOrderByProductCodeAscUnitCodeAsc().stream()
				.anyMatch(pack -> pack.getProduct().getId().equals(id) && !pack.getUnit().isActive())) {
			throw conflict("Reactivate the product's packaging units before activating the product");
		}
		item.update(request.code(), request.name(), request.active());
		item.define(type, variety, grade, unit);
		products.saveAndFlush(item);
		record(actor.getId(), id, "PRODUCT", item.getId());
		return ProductResponse.from(item);
	}

	@Transactional
	public PackagingResponse savePackaging(PackagingRequest request) {
		var actor = access.requireGlobalPermission(Permission.CATALOGUE_MANAGE);
		var product = findForUpdate(products, request.productId());
		var unit = findForUpdate(units, request.unitId());
		requireActive(true, product, unit);
		if (unit.getDimension() != UnitOfMeasure.Dimension.PACKAGING) {
			throw badRequest("Only packaging units can have a product-specific conversion");
		}
		var item = packaging.findByProductIdAndUnitId(product.getId(), unit.getId())
			.orElseGet(() -> new ProductPackaging(product, unit));
		var previousId = item.getId();
		item.update(request.quantityInBaseUnit());
		packaging.saveAndFlush(item);
		record(actor.getId(), previousId, "PACKAGING", item.getId());
		return PackagingResponse.from(item);
	}

	public ConversionResponse convert(ConvertRequest request) {
		access.requirePermission(Permission.CATALOGUE_VIEW);
		var product = find(products, request.productId());
		var from = find(units, request.fromUnitId());
		var base = product.getBaseUnit();
		requireActive(true, product, product.getProduceType(), from, base, product.getVariety(), product.getGrade());
		BigDecimal quantity;
		if (from.getDimension() == UnitOfMeasure.Dimension.PACKAGING) {
			var pack = packaging.findByProductIdAndUnitId(product.getId(), from.getId())
				.orElseThrow(() -> badRequest("No fixed packaging conversion is defined for this product"));
			quantity = request.quantity().multiply(pack.getQuantityInBaseUnit());
		}
		else {
			if (from.getDimension() != base.getDimension()) {
				throw badRequest("Cannot convert between incompatible measurement dimensions");
			}
			quantity = request.quantity().multiply(from.getBaseFactor())
				.divide(base.getBaseFactor(), 6, RoundingMode.HALF_UP);
		}
		return new ConversionResponse(quantity.setScale(6, RoundingMode.HALF_UP), base.getCode());
	}

	private ProductClassification classification(UUID id, ProduceType type, ProductClassification.Kind kind) {
		if (id == null) {
			return null;
		}
		var item = findForUpdate(classifications, id);
		if (item.getKind() != kind || !item.getProduceType().getId().equals(type.getId())) {
			throw badRequest("Variety and grade must have the correct kind and belong to the selected produce type");
		}
		return item;
	}

	private static <T> T find(JpaRepository<T, UUID> repository, UUID id) {
		return repository.findById(id)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Catalogue record not found"));
	}

	private ProduceType findForUpdate(ProduceTypeRepository repository, UUID id) {
		return repository.findByIdForUpdate(id)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Catalogue record not found"));
	}

	private ProductClassification findForUpdate(ClassificationRepository repository, UUID id) {
		return repository.findByIdForUpdate(id)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Catalogue record not found"));
	}

	private UnitOfMeasure findForUpdate(UnitRepository repository, UUID id) {
		return repository.findByIdForUpdate(id)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Catalogue record not found"));
	}

	private Product findForUpdate(ProductRepository repository, UUID id) {
		return repository.findByIdForUpdate(id)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Catalogue record not found"));
	}

	private static void requireActive(boolean active, CatalogueItem... items) {
		for (var item : items) {
			if (active && item != null && !item.isActive()) {
				throw badRequest("Active records and conversions require active catalogue dependencies");
			}
		}
	}

	private void record(UUID actor, UUID previousId, String type, UUID id) {
		audit.save(new AuditEvent(actor, previousId == null ? "CATALOGUE_CREATED" : "CATALOGUE_UPDATED", type, id));
	}

	private static boolean equal(BigDecimal left, BigDecimal right) {
		return left == null ? right == null : right != null && left.compareTo(right) == 0;
	}

	private static ResponseStatusException badRequest(String detail) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
	}

	private static ResponseStatusException conflict(String detail) {
		return new ResponseStatusException(HttpStatus.CONFLICT, detail);
	}
}
