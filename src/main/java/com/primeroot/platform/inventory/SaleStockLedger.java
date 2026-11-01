package com.primeroot.platform.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class SaleStockLedger {
	private final StockBatchRepository batches;
	private final StockMovementRepository movements;

	public SaleStockLedger(StockBatchRepository batches, StockMovementRepository movements) {
		this.batches = batches;
		this.movements = movements;
	}

	public void checkAvailable(UUID branch, UUID product, BigDecimal quantity, Currency currency) {
		var remaining = quantity;
		int page = 0;
		while (remaining.signum() > 0) {
			var candidates = batches.saleCandidates(branch, product, LocalDate.now(ZoneOffset.UTC), PageRequest.of(page++, 100));
			if (candidates.isEmpty()) throw conflict("Insufficient unexpired stock; the entire sale was not posted");
			for (var batch : candidates) {
				requireCost(batch, currency);
				remaining = remaining.subtract(remaining.min(batch.getQuantity()));
				if (remaining.signum() == 0) break;
			}
		}
	}

	public List<Allocation> allocate(StockOperation operation, UUID productId, BigDecimal quantity, Currency currency) {
		var remaining = quantity;
		var result = new ArrayList<Allocation>();
		while (remaining.signum() > 0) {
			var candidates = batches.saleCandidates(operation.getBranch().getId(), productId,
				LocalDate.now(ZoneOffset.UTC), PageRequest.of(0, 100));
			if (candidates.isEmpty()) throw conflict("Insufficient unexpired stock; the entire sale was not posted");
			for (var batch : candidates) {
				requireCost(batch, currency);
				var taken = remaining.min(batch.getQuantity());
				var after = batch.getQuantity().subtract(taken);
				// Carry residual cents forward; a full depletion consumes the exact remaining value.
				var cost = batch.getRemainingCost().multiply(taken)
					.divide(batch.getQuantity(), currency.getDefaultFractionDigits(), RoundingMode.HALF_UP);
				batch.setQuantity(after);
				batch.setRemainingCost(batch.getRemainingCost().subtract(cost));
				var posting = new StockMovement(operation, batch, StockOperation.Kind.SALE, taken.negate(), null);
				posting.setCostChange(cost.negate());
				var movement = movements.save(posting);
				result.add(new Allocation(movement, taken, cost));
				remaining = remaining.subtract(taken);
				if (remaining.signum() == 0) break;
			}
			batches.flush();
		}
		return result;
	}

	public void restore(StockOperation reversal, UUID saleId) {
		for (var original : movements.findByOperationIdOrderById(saleId)) {
			if (original.getKind() != StockOperation.Kind.SALE || movements.existsByReversalOfId(original.getId())) {
				throw conflict("Sale stock has already been reversed or is inconsistent");
			}
			var batch = original.getBatch();
			var balance = batch.getQuantity().subtract(original.getQuantity());
			if (balance.compareTo(new BigDecimal("9999999999999.999999")) > 0) {
				throw conflict("Returning this sale exceeds the supported batch balance");
			}
			batch.setQuantity(balance);
			var returnedCost = original.getCostChange().negate();
			batch.setRemainingCost(batch.getRemainingCost().add(returnedCost));
			var movement = new StockMovement(reversal, batch, StockOperation.Kind.SALE_VOID,
				original.getQuantity().negate(), original);
			movement.setCostChange(returnedCost);
			movements.save(movement);
		}
	}

	public record Allocation(StockMovement movement, BigDecimal quantity, BigDecimal cost) {
	}

	private static void requireCost(StockBatch batch, Currency currency) {
		if (batch.getRemainingCost() == null || !currency.getCurrencyCode().equals(batch.getCurrency())) {
			throw conflict("The next FEFO batch has unknown cost or a different currency: " + batch.getLotCode());
		}
	}

	private static ResponseStatusException conflict(String detail) {
		return new ResponseStatusException(HttpStatus.CONFLICT, detail);
	}
}
