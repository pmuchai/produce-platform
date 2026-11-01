package com.primeroot.platform.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.primeroot.platform.access.AccessService;
import com.primeroot.platform.access.Branch;
import com.primeroot.platform.access.BranchRepository;
import com.primeroot.platform.access.Permission;
import com.primeroot.platform.audit.AuditEvent;
import com.primeroot.platform.audit.AuditRepository;
import com.primeroot.platform.catalogue.CatalogueQuantityService;
import com.primeroot.platform.catalogue.Product;
import com.primeroot.platform.catalogue.ProductRepository;
import com.primeroot.platform.catalogue.UnitOfMeasure;
import com.primeroot.platform.catalogue.UnitRepository;
import com.primeroot.platform.inventory.InventoryDtos.Balance;
import com.primeroot.platform.inventory.InventoryDtos.BatchPage;
import com.primeroot.platform.inventory.InventoryDtos.BatchResponse;
import com.primeroot.platform.inventory.InventoryDtos.BranchOption;
import com.primeroot.platform.inventory.InventoryDtos.ChangeRequest;
import com.primeroot.platform.inventory.InventoryDtos.MovementResponse;
import com.primeroot.platform.inventory.InventoryDtos.OperationResponse;
import com.primeroot.platform.inventory.InventoryDtos.ProductOption;
import com.primeroot.platform.inventory.InventoryDtos.ReceiptRequest;
import com.primeroot.platform.inventory.InventoryDtos.ReverseRequest;
import com.primeroot.platform.inventory.InventoryDtos.Snapshot;
import com.primeroot.platform.inventory.InventoryDtos.ThresholdRequest;
import com.primeroot.platform.inventory.InventoryDtos.ThresholdResponse;
import com.primeroot.platform.inventory.InventoryDtos.UnitOption;
import com.primeroot.platform.inventory.StockOperation.Kind;
import com.primeroot.platform.ui.DashboardPeriod;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Page;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class InventoryService {
	private static final BigDecimal MAXIMUM = new BigDecimal("9999999999999.999999");
	private final AccessService access;
	private final BranchRepository branches;
	private final ProductRepository products;
	private final UnitRepository units;
	private final CatalogueQuantityService quantities;
	private final StockOperationRepository operations;
	private final StockBatchRepository batches;
	private final StockMovementRepository movements;
	private final StockThresholdRepository thresholds;
	private final AuditRepository audit;
	private final JsonMapper json;
	private final Currency businessCurrency;

	public InventoryService(AccessService access, BranchRepository branches, ProductRepository products,
			UnitRepository units, CatalogueQuantityService quantities, StockOperationRepository operations,
			StockBatchRepository batches, StockMovementRepository movements,
			StockThresholdRepository thresholds, AuditRepository audit, JsonMapper json,
			@Value("${platform.inventory.currency}") String currency) {
		this.access = access;
		this.branches = branches;
		this.products = products;
		this.units = units;
		this.quantities = quantities;
		this.operations = operations;
		this.batches = batches;
		this.movements = movements;
		this.thresholds = thresholds;
		this.audit = audit;
		this.json = json;
		this.businessCurrency = currency(currency);
	}

	@Transactional
	public OperationResponse receive(ReceiptRequest request) {
		var actor = access.requirePermission(Permission.STOCK_RECEIVE);
		var branch = lockBranch(request.branchId());
		var hash = fingerprint(Kind.RECEIPT, actor.getId(), request);
		var previous = previous(request.requestId(), hash);
		if (previous != null) return previous;
		if (!businessCurrency.getCurrencyCode().equalsIgnoreCase(request.currency())) {
			throw invalid("Receipts must use the configured business currency: " + businessCurrency.getCurrencyCode());
		}
		if (!branch.isActive()) throw invalid("Receive stock into an active branch");
		var today = LocalDate.now(ZoneOffset.UTC);
		if (request.receivedDate().isBefore(LocalDate.of(1970, 1, 1)) || request.receivedDate().isAfter(today)) {
			throw invalid("Received date must be between 1970-01-01 and today in UTC");
		}
		var operation = operations.saveAndFlush(new StockOperation(request.requestId(), branch, actor.getId(),
			Kind.RECEIPT, hash, request.source().trim(), request.notes()));
		for (var line : request.lines()) {
			if (line.expiryDate() != null && (line.expiryDate().isBefore(request.receivedDate())
					|| line.expiryDate().getYear() > 9999)) {
				throw invalid("Expiry/best-before date must be on or after receipt and no later than 9999-12-31");
			}
			var conversion = quantities.resolve(line.productId(), line.unitId(), line.quantity(), line.measuredBaseQuantity());
			var batch = batches.save(new StockBatch(operation, line, request.receivedDate(), conversion, businessCurrency));
			movements.save(new StockMovement(operation, batch, Kind.RECEIPT, conversion.quantity(), null));
		}
		record(operation);
		return response(operation.getId());
	}

	@Transactional
	public OperationResponse spoil(ChangeRequest request) {
		return change(request, Kind.SPOILAGE, Permission.STOCK_SPOILAGE);
	}

	@Transactional
	public OperationResponse adjust(ChangeRequest request) {
		return change(request, Kind.ADJUSTMENT, Permission.STOCK_ADJUST);
	}

	private OperationResponse change(ChangeRequest request, Kind kind, Permission permission) {
		var actor = access.requirePermission(permission);
		var branch = lockBranch(request.branchId());
		var hash = fingerprint(kind, actor.getId(), request);
		var previous = previous(request.requestId(), hash);
		if (previous != null) return previous;
		if (request.quantity().signum() == 0 || kind == Kind.SPOILAGE && request.quantity().signum() < 0) {
			throw invalid("Spoilage requires a positive quantity; adjustments require a nonzero signed quantity");
		}
		var batch = batch(request.batchId(), branch.getId());
		var quantity = kind == Kind.SPOILAGE ? request.quantity().negate() : request.quantity();
		var cost = changeCost(batch, quantity);
		apply(batch, quantity, cost);
		var operation = operations.saveAndFlush(new StockOperation(request.requestId(), branch, actor.getId(),
			kind, hash, null, request.reason().trim()));
		var movement = new StockMovement(operation, batch, kind, quantity, null);
		movement.setCostChange(cost);
		movements.save(movement);
		record(operation);
		return response(operation.getId());
	}

	@Transactional
	public OperationResponse reverse(ReverseRequest request) {
		var actor = access.requirePermission(Permission.STOCK_ADJUST);
		var branch = lockBranch(request.branchId());
		var hash = fingerprint(Kind.REVERSAL, actor.getId(), request);
		var previous = previous(request.requestId(), hash);
		if (previous != null) return previous;
		var original = movements.findById(request.movementId())
			.filter(item -> item.getBatch().getBranch().getId().equals(branch.getId()))
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Stock movement not found in this branch"));
		if (original.getKind() == Kind.SALE || original.getKind() == Kind.SALE_VOID) {
			throw conflict("Sales stock can only be reversed through a full sale void");
		}
		if (original.getKind() == Kind.TRANSFER_OUT || original.getKind() == Kind.TRANSFER_IN) {
			throw conflict("Transfer movements are final and cannot be reversed through stock adjustments");
		}
		if (original.getReversalOf() != null || movements.existsByReversalOfId(original.getId())) {
			throw conflict("This movement is a reversal or has already been reversed");
		}
		var quantity = original.getQuantity().negate();
		var originalCost = original.getCostChange() == null ? original.getBatch().proportionalCost(original.getQuantity())
			: original.getCostChange();
		var cost = originalCost == null ? null : originalCost.negate();
		apply(original.getBatch(), quantity, cost);
		var operation = operations.saveAndFlush(new StockOperation(request.requestId(), branch, actor.getId(),
			Kind.REVERSAL, hash, null, request.reason().trim()));
		var movement = new StockMovement(operation, original.getBatch(), original.getKind(), quantity, original);
		movement.setCostChange(cost);
		movements.save(movement);
		record(operation);
		return response(operation.getId());
	}

	@Transactional
	public ThresholdResponse threshold(ThresholdRequest request) {
		var actor = access.requirePermission(Permission.STOCK_ADJUST);
		var branch = lockBranch(request.branchId());
		var product = products.findById(request.productId())
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
		var existing = thresholds.findByBranchIdAndProductId(branch.getId(), product.getId());
		long version = existing.map(StockThreshold::getVersion).orElse(-1L);
		if (version != request.expectedVersion()) {
			throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Threshold changed; reload before editing");
		}
		var threshold = existing.orElseGet(() -> new StockThreshold(branch, product));
		threshold.setMinimumQuantity(request.minimumQuantity());
		thresholds.saveAndFlush(threshold);
		audit.save(new AuditEvent(actor.getId(), "STOCK_THRESHOLD_UPDATED", "STOCK_THRESHOLD", threshold.getId()));
		return ThresholdResponse.from(threshold);
	}

	public Snapshot snapshot(UUID branchId, DashboardPeriod period, int page) {
		var actor = access.requirePermission(Permission.STOCK_VIEW);
		if (branchId != null) access.requireBranch(branchId);
		if (page < 0 || page > 1_000_000) throw invalid("Page must be between 0 and 1000000");
		var visible = actor.isAllBranches() ? branches.findAllByOrderByCode() : branches.findAccessibleBranches(actor.getId());
		if (branchId != null && visible.stream().noneMatch(branch -> branch.getId().equals(branchId))) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch not found");
		}
		var ids = visible.stream().map(Branch::getId).filter(id -> branchId == null || id.equals(branchId)).toList();
		var today = LocalDate.now(ZoneOffset.UTC);
		var productEntities = products.findAllByOrderByCode();
		Map<UUID, Product> productsById = new HashMap<>();
		productEntities.forEach(product -> productsById.put(product.getId(), product));
		Map<UUID, Branch> branchesById = new HashMap<>();
		visible.forEach(branch -> branchesById.put(branch.getId(), branch));
		var limits = ids.isEmpty() ? List.<StockThreshold>of() : thresholds.findByBranchIdIn(ids);
		Map<Key, BigDecimal> current = new LinkedHashMap<>();
		Map<Key, Product> knownProducts = new LinkedHashMap<>();
		Map<Key, Branch> knownBranches = new LinkedHashMap<>();
		Map<Key, StockThreshold> minimums = new HashMap<>();
		if (!ids.isEmpty()) {
			for (var row : batches.currentBalances(ids)) {
				var branch = branchesById.get((UUID) row[0]);
				var product = productsById.get((UUID) row[1]);
				var key = new Key(branch.getId(), product.getId());
				current.put(key, (BigDecimal) row[2]);
				knownProducts.put(key, product);
				knownBranches.put(key, branch);
			}
		}
		for (var limit : limits) {
			var key = new Key(limit.getBranch().getId(), limit.getProduct().getId());
			knownProducts.put(key, limit.getProduct());
			knownBranches.put(key, limit.getBranch());
			minimums.put(key, limit);
		}
		Map<Key, BigDecimal> asOf = new HashMap<>();
		if (!ids.isEmpty()) {
			for (var row : movements.balances(ids, period.until())) {
				asOf.put(new Key((UUID) row[0], (UUID) row[1]), (BigDecimal) row[2]);
			}
		}
		var totals = totals(ids, period.from(), period.until());
		var previousTotals = totals(ids, period.previousFrom(), period.from());
		var balances = new ArrayList<Balance>();
		for (var key : knownProducts.keySet()) {
			var product = knownProducts.get(key);
			var limit = minimums.get(key);
			var quantity = current.getOrDefault(key, BigDecimal.ZERO);
			balances.add(new Balance(key.branch(), knownBranches.get(key).getName(), key.product(), product.getName(),
				product.getBaseUnit().getCode(), quantity, asOf.getOrDefault(key, BigDecimal.ZERO),
				total(totals, key, Kind.RECEIPT), total(previousTotals, key, Kind.RECEIPT),
				total(totals, key, Kind.SPOILAGE).negate(), total(previousTotals, key, Kind.SPOILAGE).negate(),
				limit == null ? null : limit.getMinimumQuantity(), limit == null ? -1 : limit.getVersion(),
				limit != null && quantity.compareTo(limit.getMinimumQuantity()) <= 0));
		}
		balances.sort(Comparator.comparing(Balance::branch).thenComparing(Balance::product).thenComparing(Balance::productId));
		var history = ids.isEmpty() ? Page.<StockMovement>empty()
			: movements.history(ids, period.from(), period.until(), PageRequest.of(page, 100));
		return new Snapshot(visible.stream().map(branch -> new BranchOption(branch.getId(), branch.getName(), branch.isActive())).toList(),
			productEntities.stream().map(product -> new ProductOption(product.getId(), product.getCode(),
				product.getName(), product.getBaseUnit().getCode(), product.isActive())).toList(),
			units.findAllByOrderByCode().stream().filter(UnitOfMeasure::isActive)
				.map(unit -> new UnitOption(unit.getId(), unit.getCode())).toList(),
			balances, movementResponses(history.getContent()), history.getTotalElements(), page,
			ids.isEmpty() ? 0 : batches.lowStockCount(ids),
			ids.isEmpty() ? 0 : batches.nearExpiryCount(ids, today.plusDays(7)),
			businessCurrency.getCurrencyCode());
	}

	public BatchPage batchPage(UUID branchId, String search, int page) {
		var actor = access.requirePermission(Permission.STOCK_VIEW);
		if (branchId != null) access.requireBranch(branchId);
		if (page < 0 || page > 1_000_000) throw invalid("Page must be between 0 and 1000000");
		if (search != null && search.length() > 120) throw invalid("Batch search must be at most 120 characters");
		var visible = actor.isAllBranches() ? branches.findAllByOrderByCode() : branches.findAccessibleBranches(actor.getId());
		if (branchId != null && visible.stream().noneMatch(branch -> branch.getId().equals(branchId))) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch not found");
		}
		var ids = visible.stream().map(Branch::getId).filter(id -> branchId == null || id.equals(branchId)).toList();
		var term = search == null || search.isBlank() ? null : search.trim().toLowerCase(Locale.ROOT);
		var pageable = PageRequest.of(page, 100);
		var result = ids.isEmpty() ? Page.<StockBatch>empty(pageable) : batches.search(ids, "%" + (term == null ? "" : term) + "%", pageable);
		var today = LocalDate.now(ZoneOffset.UTC);
		return new BatchPage(result.getContent().stream().map(batch -> BatchResponse.from(batch, today)).toList(),
			result.getTotalElements(), page, term == null ? "" : term);
	}

	private Branch lockBranch(UUID id) {
		access.requireBranch(id);
		// Serialize postings per branch, including first receipts and idempotency checks.
		return branches.lockForStockUpdate(id)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch not found"));
	}

	private StockBatch batch(UUID id, UUID branchId) {
		return batches.findById(id).filter(batch -> batch.getBranch().getId().equals(branchId))
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Batch not found in this branch"));
	}

	private BigDecimal changeCost(StockBatch batch, BigDecimal change) {
		if (batch.getRemainingCost() == null) return null;
		if (change.signum() > 0) return batch.proportionalCost(change);
		if (batch.getQuantity().signum() == 0) throw conflict("Insufficient stock in this batch");
		return batch.getRemainingCost().multiply(change)
			.divide(batch.getQuantity(), Currency.getInstance(batch.getCurrency()).getDefaultFractionDigits(),
				java.math.RoundingMode.HALF_UP);
	}

	private void apply(StockBatch batch, BigDecimal change, BigDecimal cost) {
		var balance = batch.getQuantity().add(change);
		if (balance.signum() < 0) throw conflict("Insufficient stock in this batch");
		if (balance.compareTo(MAXIMUM) > 0) throw invalid("Batch quantity exceeds the supported quantity range");
		if (cost != null && batch.getRemainingCost() != null) {
			var value = batch.getRemainingCost().add(cost);
			if (value.signum() < 0) throw conflict("This reversal exceeds the remaining batch cost; review intervening stock corrections");
			if (balance.signum() == 0 && value.signum() != 0) {
				throw conflict("This reversal leaves a carrying value without stock; review intervening stock corrections");
			}
			batch.setRemainingCost(value);
		}
		batch.setQuantity(balance);
	}

	private OperationResponse previous(UUID requestId, String hash) {
		var previous = operations.findById(requestId);
		if (previous.isEmpty()) return null;
		if (!previous.get().getRequestHash().equals(hash)) {
			throw conflict("Request ID already used for a different operation; reload and review before posting again");
		}
		return response(requestId);
	}

	private OperationResponse response(UUID id) {
		return new OperationResponse(id, movementResponses(movements.findByOperationIdOrderById(id)));
	}

	private List<MovementResponse> movementResponses(List<StockMovement> items) {
		var ids = items.stream().map(StockMovement::getId).toList();
		var reversedIds = ids.isEmpty() ? Set.<UUID>of() : Set.copyOf(movements.reversedMovementIds(ids));
		return items.stream().map(movement -> MovementResponse.from(movement, reversedIds.contains(movement.getId()))).toList();
	}

	private void record(StockOperation operation) {
		audit.save(new AuditEvent(operation.getActorId(), "STOCK_" + operation.getKind(), "STOCK_OPERATION", operation.getId()));
	}

	private record Key(UUID branch, UUID product) {
	}

	private record TotalKey(Key key, Kind kind) {
	}

	private Map<TotalKey, BigDecimal> totals(List<UUID> ids, Instant from, Instant until) {
		Map<TotalKey, BigDecimal> totals = new HashMap<>();
		if (!ids.isEmpty()) {
			for (var row : movements.totals(ids, from, until)) {
				totals.put(new TotalKey(new Key((UUID) row[0], (UUID) row[1]), (Kind) row[2]), (BigDecimal) row[3]);
			}
		}
		return totals;
	}

	private static BigDecimal total(Map<TotalKey, BigDecimal> totals, Key key, Kind kind) {
		return totals.getOrDefault(new TotalKey(key, kind), BigDecimal.ZERO);
	}

	private String fingerprint(Kind kind, UUID actor, Object request) {
		return PostingFingerprint.of(json, kind, actor, request);
	}

	private static ResponseStatusException invalid(String detail) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
	}

	private static Currency currency(String code) {
		final Currency currency;
		try {
			currency = Currency.getInstance(code.toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("platform.inventory.currency must be a supported three-letter ISO currency code", exception);
		}
		if (currency.getDefaultFractionDigits() < 0 || currency.getDefaultFractionDigits() > 6) {
			throw new IllegalArgumentException("platform.inventory.currency must have between zero and six fractional digits");
		}
		return currency;
	}

	private static ResponseStatusException conflict(String detail) {
		return new ResponseStatusException(HttpStatus.CONFLICT, detail);
	}
}
