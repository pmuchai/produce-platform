package com.primeroot.platform.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

import com.primeroot.platform.access.AccessService;
import com.primeroot.platform.access.Branch;
import com.primeroot.platform.access.BranchRepository;
import com.primeroot.platform.access.Permission;
import com.primeroot.platform.audit.AuditEvent;
import com.primeroot.platform.audit.AuditRepository;
import com.primeroot.platform.catalogue.ProductRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
@Transactional(readOnly = true)
public class TransferService {
	private final AccessService access;
	private final BranchRepository branches;
	private final ProductRepository products;
	private final StockBatchRepository batches;
	private final StockMovementRepository movements;
	private final StockOperationRepository operations;
	private final StockTransferRepository transfers;
	private final TransferAllocationRepository allocations;
	private final AuditRepository audit;
	private final JsonMapper json;
	private final jakarta.persistence.EntityManager entityManager;

	public TransferService(AccessService access, BranchRepository branches, ProductRepository products,
			StockBatchRepository batches, StockMovementRepository movements, StockOperationRepository operations,
			StockTransferRepository transfers, TransferAllocationRepository allocations, AuditRepository audit, JsonMapper json,
			jakarta.persistence.EntityManager entityManager) {
		this.access = access;
		this.branches = branches;
		this.products = products;
		this.batches = batches;
		this.movements = movements;
		this.operations = operations;
		this.transfers = transfers;
		this.allocations = allocations;
		this.audit = audit;
		this.json = json;
		this.entityManager = entityManager;
	}

	public TransferDtos.Entry entry() {
		access.requirePermission(Permission.TRANSFER_DISPATCH);
		return new TransferDtos.Entry(visibleBranches().stream().filter(Branch::isActive)
			.map(b -> new TransferDtos.Option(b.getId(), b.getName())).toList(),
			branches.findAllByOrderByCode().stream().filter(Branch::isActive)
				.map(b -> new TransferDtos.Option(b.getId(), b.getName())).toList(),
			products.findAllByOrderByCode().stream().filter(p -> p.isActive() && p.getBaseUnit().isActive()
				&& p.getProduceType().isActive() && (p.getVariety() == null || p.getVariety().isActive()))
				.map(p -> new TransferDtos.Option(p.getId(), p.getCode() + " / " + p.getName()
					+ " (" + p.getBaseUnit().getCode() + ")")).toList());
	}

	public TransferDtos.History history(StockTransfer.Status status, int page) {
		access.requirePermission(Permission.TRANSFER_VIEW);
		if (page < 0 || page > 1_000_000) throw invalid("Page must be between 0 and 1000000");
		var ids = visibleBranches().stream().map(Branch::getId).toList();
		if (ids.isEmpty()) return new TransferDtos.History(List.of(), page, 0, 0);
		var result = transfers.history(ids, status, PageRequest.of(page, 50));
		return new TransferDtos.History(result.stream().map(this::summary).toList(), page,
			result.getTotalPages(), result.getTotalElements());
	}

	public TransferDtos.Detail detail(UUID id) {
		access.requirePermission(Permission.TRANSFER_VIEW);
		var transfer = find(id);
		requireVisible(transfer);
		return response(transfer);
	}

	@Transactional
	public TransferDtos.Detail dispatch(TransferDtos.Dispatch request) {
		var actor = access.requirePermission(Permission.TRANSFER_DISPATCH);
		access.requireBranch(request.sourceId());
		if (request.sourceId().equals(request.destinationId())) throw invalid("Choose different source and destination branches");
		var locked = lockBranches(request.sourceId(), request.destinationId());
		var hash = PostingFingerprint.of(json, StockOperation.Kind.TRANSFER_OUT, actor.getId(), request);
		if (previous(request.requestId(), hash)) return response(find(request.requestId()));
		var source = locked.get(request.sourceId());
		var destination = locked.get(request.destinationId());
		requireActive(source);
		requireActive(destination);
		var required = new TreeMap<UUID, BigDecimal>();
		for (var line : request.lines()) {
			var product = products.findById(line.productId())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
			if (!product.isActive() || !product.getBaseUnit().isActive() || !product.getProduceType().isActive()
					|| product.getVariety() != null && !product.getVariety().isActive()) {
				throw invalid("Transfer an active product and classification");
			}
			required.merge(line.productId(), line.quantity(), BigDecimal::add);
		}
		var operation = operations.saveAndFlush(new StockOperation(request.requestId(), source, actor.getId(),
			StockOperation.Kind.TRANSFER_OUT, hash, "Inter-branch transfer", request.notes()));
		var transfer = transfers.save(new StockTransfer(operation, destination));
		int count = 0;
		for (var item : required.entrySet()) {
			var remaining = item.getValue();
			while (remaining.signum() > 0) {
				var candidates = batches.saleCandidates(source.getId(), item.getKey(),
					LocalDate.now(ZoneOffset.UTC), PageRequest.of(0, 100));
				if (candidates.isEmpty()) throw conflict("Insufficient unexpired stock; the entire transfer was not dispatched");
				for (var batch : candidates) {
					if (++count > 1000) throw invalid("A transfer can allocate at most 1000 batches; use smaller transfers");
					var taken = remaining.min(batch.getQuantity());
					var cost = portion(batch.getRemainingCost(), taken, batch.getQuantity(), batch.getCurrency());
					batch.setQuantity(batch.getQuantity().subtract(taken));
					batch.setRemainingCost(cost == null ? null : batch.getRemainingCost().subtract(cost));
					var movement = new StockMovement(operation, batch, StockOperation.Kind.TRANSFER_OUT, taken.negate(), null);
					movement.setCostChange(cost == null ? null : cost.negate());
					allocations.save(new TransferAllocation(transfer, movements.save(movement), taken, cost));
					remaining = remaining.subtract(taken);
					if (remaining.signum() == 0) break;
				}
				batches.flush();
			}
		}
		audit.save(new AuditEvent(actor.getId(), "TRANSFER_DISPATCHED", "STOCK_TRANSFER", transfer.getId()));
		transfers.flush();
		return response(transfer);
	}

	@Transactional
	public TransferDtos.Detail receive(UUID id, TransferDtos.Receive request) {
		var actor = access.requirePermission(Permission.TRANSFER_RECEIVE);
		var initial = find(id);
		var sourceId = initial.getSource().getId();
		var destinationId = initial.getDestination().getId();
		access.requireBranch(destinationId);
		var locked = lockBranches(sourceId, destinationId);
		// Do not read mutable transfer state before acquiring the shared branch stock locks.
		var hash = PostingFingerprint.of(json, StockOperation.Kind.TRANSFER_IN, actor.getId(), new ReceiveIdentity(id, request));
		if (previous(request.requestId(), hash)) {
			return response(reload(id));
		}
		var transfer = reload(id);
		if (transfer.getStatus() != StockTransfer.Status.IN_TRANSIT) throw conflict("This transfer has already been finally received");
		requireActive(locked.get(destinationId));
		var items = allocations.findByTransferIdOrderById(id);
		var supplied = new HashMap<UUID, TransferDtos.Arrival>();
		for (var item : request.allocations()) {
			if (supplied.put(item.allocationId(), item) != null) throw invalid("Each allocation must appear exactly once");
		}
		if (supplied.size() != items.size()) throw invalid("Final receipt must account for every allocation exactly once");
		for (var allocation : items) {
			var item = supplied.get(allocation.getId());
			if (item == null || item.receivedQuantity().add(item.shortageQuantity()).add(item.damagedQuantity())
					.compareTo(allocation.getQuantity()) != 0) {
				throw invalid("Received, shortage and damaged quantities must equal each dispatched allocation");
			}
			if (item.shortageQuantity().add(item.damagedQuantity()).signum() > 0
					&& (item.reason() == null || item.reason().isBlank())) throw invalid("Every discrepancy requires a reason");
		}
		var operation = operations.saveAndFlush(new StockOperation(request.requestId(), locked.get(destinationId), actor.getId(),
			StockOperation.Kind.TRANSFER_IN, hash, "Transfer " + id, request.notes()));
		for (var allocation : items) {
			var item = supplied.get(allocation.getId());
			var source = allocation.getMovement().getBatch();
			var arrivedCost = portion(allocation.getCost(), item.receivedQuantity(), allocation.getQuantity(), source.getCurrency());
			StockBatch destinationBatch = null;
			if (item.receivedQuantity().signum() > 0) {
				destinationBatch = batches.save(new StockBatch(operation, source, item.receivedQuantity(), arrivedCost));
				var movement = new StockMovement(operation, destinationBatch, StockOperation.Kind.TRANSFER_IN,
					item.receivedQuantity(), null);
				movement.setCostChange(arrivedCost);
				movements.save(movement);
			}
			allocation.receive(item, destinationBatch, arrivedCost);
			if (item.shortageQuantity().add(item.damagedQuantity()).signum() > 0) {
				audit.save(new AuditEvent(actor.getId(), "TRANSFER_DISCREPANCY", "TRANSFER_ALLOCATION", allocation.getId()));
			}
		}
		transfer.receive(operation);
		audit.save(new AuditEvent(actor.getId(), "TRANSFER_RECEIVED", "STOCK_TRANSFER", id));
		transfers.flush();
		return response(transfer);
	}

	private StockTransfer reload(UUID id) {
		var transfer = find(id);
		entityManager.refresh(transfer);
		return transfer;
	}

	private Map<UUID, Branch> lockBranches(UUID source, UUID destination) {
		var result = new HashMap<UUID, Branch>();
		// All stock writers use branch locks; deterministic ordering also prevents opposite-route deadlocks.
		for (var id : java.util.stream.Stream.of(source, destination).sorted().toList()) {
			result.put(id, branches.lockForStockUpdate(id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch not found")));
		}
		return result;
	}

	private boolean previous(UUID id, String hash) {
		var previous = operations.findById(id);
		if (previous.isEmpty()) return false;
		if (!previous.get().getRequestHash().equals(hash)) throw conflict("Request ID already used with different details or actor");
		return true;
	}

	private List<Branch> visibleBranches() {
		var actor = access.currentUser();
		return actor.isAllBranches() ? branches.findAllByOrderByCode() : branches.findAccessibleBranches(actor.getId());
	}

	private void requireVisible(StockTransfer transfer) {
		if (visibleBranches().stream().noneMatch(b -> b.getId().equals(transfer.getSource().getId())
				|| b.getId().equals(transfer.getDestination().getId()))) {
			throw new AccessDeniedException("Access to the source or destination branch is required");
		}
	}

	private StockTransfer find(UUID id) {
		return transfers.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transfer not found"));
	}

	private TransferDtos.Summary summary(StockTransfer transfer) {
		var dispatch = operations.findById(transfer.getId()).orElseThrow();
		var receipt = transfer.getReceiveOperation();
		return new TransferDtos.Summary(transfer.getId(), transfer.getSource().getId(), transfer.getSourceName(),
			transfer.getDestination().getId(), transfer.getDestinationName(), transfer.getStatus(), dispatch.getPostedAt(),
			receipt == null ? null : receipt.getPostedAt());
	}

	private TransferDtos.Detail response(StockTransfer transfer) {
		var permissions = access.permissions();
		boolean profit = permissions.contains(Permission.PROFIT_VIEW);
		boolean canReceive = permissions.contains(Permission.TRANSFER_RECEIVE)
			&& transfer.getStatus() == StockTransfer.Status.IN_TRANSIT && transfer.getDestination().isActive()
			&& visibleBranches().stream().anyMatch(b -> b.getId().equals(transfer.getDestination().getId()));
		var dispatch = operations.findById(transfer.getId()).orElseThrow();
		var receipt = transfer.getReceiveOperation();
		var rows = allocations.findByTransferIdOrderById(transfer.getId()).stream().map(a -> {
			var batch = a.getMovement().getBatch();
			return new TransferDtos.Allocation(a.getId(), batch.getId(), a.getMovement().getId(),
				a.getDestinationBatch() == null ? null : a.getDestinationBatch().getId(), batch.getProduct().getId(),
				a.getProductCode(), a.getProductName(), a.getProduceType(), a.getVariety(), a.getGrade(), a.getBaseUnit(),
				batch.getLotCode(), batch.getReceivedDate(), batch.getExpiryDate(), batch.getCurrency(),
				batch.getInputUnit().getId(), batch.getInputUnit().getCode(),
				profit ? batch.getPurchaseUnitCost() : null, a.getQuantity(), profit ? a.getCost() : null,
				a.getReceivedQuantity(), a.getShortageQuantity(), a.getDamagedQuantity(),
				profit ? a.getReceivedCost() : null, profit ? a.getLossCost() : null, a.getReason());
		}).toList();
		return new TransferDtos.Detail(summary(transfer), dispatch.getActorId(), receipt == null ? null : receipt.getActorId(),
			dispatch.getNotes(), receipt == null ? null : receipt.getNotes(), canReceive, rows);
	}

	private static BigDecimal portion(BigDecimal cost, BigDecimal quantity, BigDecimal total, String currency) {
		if (cost == null) return null;
		if (quantity.compareTo(total) == 0) return cost;
		return cost.multiply(quantity).divide(total, Currency.getInstance(currency).getDefaultFractionDigits(), RoundingMode.HALF_UP);
	}
	private record ReceiveIdentity(UUID transferId, TransferDtos.Receive request) {}
	private static void requireActive(Branch branch) {
		if (!branch.isActive()) throw conflict("Stock transfers require an active branch");
	}
	private static ResponseStatusException invalid(String detail) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
	}
	private static ResponseStatusException conflict(String detail) {
		return new ResponseStatusException(HttpStatus.CONFLICT, detail);
	}
}
