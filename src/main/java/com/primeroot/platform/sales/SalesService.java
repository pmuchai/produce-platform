package com.primeroot.platform.sales;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.primeroot.platform.access.AccessService;
import com.primeroot.platform.access.Branch;
import com.primeroot.platform.access.BranchRepository;
import com.primeroot.platform.access.Permission;
import com.primeroot.platform.audit.AuditEvent;
import com.primeroot.platform.audit.AuditRepository;
import com.primeroot.platform.catalogue.CatalogueQuantityService;
import com.primeroot.platform.catalogue.ProductRepository;
import com.primeroot.platform.catalogue.UnitOfMeasure;
import com.primeroot.platform.catalogue.UnitRepository;
import com.primeroot.platform.inventory.PostingFingerprint;
import com.primeroot.platform.inventory.SaleStockLedger;
import com.primeroot.platform.inventory.StockOperation;
import com.primeroot.platform.inventory.StockOperationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
@Transactional(readOnly = true)
public class SalesService {
	private final AccessService access;
	private final BranchRepository branches;
	private final ProductRepository products;
	private final UnitRepository units;
	private final CatalogueQuantityService quantities;
	private final StockOperationRepository operations;
	private final SaleStockLedger ledger;
	private final SaleRepository sales;
	private final SaleLineRepository lines;
	private final SaleAllocationRepository allocations;
	private final AuditRepository audit;
	private final JsonMapper json;
	private final Currency currency;

	public SalesService(AccessService access, BranchRepository branches, ProductRepository products, UnitRepository units,
			CatalogueQuantityService quantities, StockOperationRepository operations, SaleStockLedger ledger,
			SaleRepository sales, SaleLineRepository lines, SaleAllocationRepository allocations, AuditRepository audit,
			JsonMapper json, @Value("${platform.inventory.currency}") String currency) {
		this.access = access;
		this.branches = branches;
		this.products = products;
		this.units = units;
		this.quantities = quantities;
		this.operations = operations;
		this.ledger = ledger;
		this.sales = sales;
		this.lines = lines;
		this.allocations = allocations;
		this.audit = audit;
		this.json = json;
		this.currency = Currency.getInstance(currency.toUpperCase(Locale.ROOT));
	}

	private Branch sellingBranch() {
		var actor = access.requirePermission(Permission.SALE_CREATE);
		if (actor.isAllBranches() || actor.getBranches().size() != 1) {
			throw conflict("Selling requires exactly one assigned branch, not business-wide or multiple-branch scope");
		}
		var branch = actor.getBranches().iterator().next();
		if (!branch.isActive()) throw conflict("Your assigned selling branch is inactive");
		access.requireBranch(branch.getId());
		return branch;
	}

	public SalesDtos.Entry entry() {
		var branch = sellingBranch();
		return new SalesDtos.Entry(branch.getId(), branch.getName(), currency.getCurrencyCode(),
			products.findAllByOrderByCode().stream().filter(product -> product.isActive() && product.getBaseUnit().isActive()
				&& product.getProduceType().isActive()
				&& (product.getVariety() == null || product.getVariety().isActive()))
				.map(product -> new SalesDtos.Option(product.getId(), product.getCode() + " / " + product.getName())).toList(),
			units.findAllByOrderByCode().stream().filter(UnitOfMeasure::isActive)
				.map(unit -> new SalesDtos.Option(unit.getId(), unit.getCode())).toList(),
			sales.customers(branch.getId(), PageRequest.of(0, 500)));
	}

	public SalesDtos.Quote quote(SalesDtos.QuoteRequest request) {
		var branch = sellingBranch();
		var saleDate = saleDate(request.saleDate());
		var total = BigDecimal.ZERO;
		var required = new java.util.LinkedHashMap<UUID, BigDecimal>();
		for (var line : request.lines()) {
			var conversion = quantities.resolve(line.productId(), line.unitId(), line.quantity(), line.measuredBaseQuantity());
			required.merge(line.productId(), conversion.quantity(), BigDecimal::add);
			total = total.add(lineAmount(line));
		}
		required.forEach((product, quantity) -> ledger.checkAvailable(branch.getId(), product, quantity, currency, saleDate));
		return new SalesDtos.Quote(currency.getCurrencyCode(), money(total));
	}

	@Transactional
	public SalesDtos.Receipt create(SalesDtos.CreateRequest request) {
		var actor = access.requirePermission(Permission.SALE_CREATE);
		var branch = lock(sellingBranch().getId(), true);
		var hash = PostingFingerprint.of(json, StockOperation.Kind.SALE, actor.getId(), request);
		if (previous(request.requestId(), hash)) return receipt(request.requestId());
		var saleDate = saleDate(request.saleDate());
		if (request.customer() != null && request.customer().isBlank()) throw invalid("Choose Walk-ins or enter a customer name");
		var reference = reference(request.paymentReference());
		if (request.paymentMethod() == Sale.PaymentMethod.CASH) {
			reference = null;
		}
		else {
			if (reference == null) throw invalid("M-Pesa and bank payments require a payment reference");
			if (sales.existsByPaymentMethodAndPaymentReference(request.paymentMethod(), reference)) {
				throw conflict("This payment reference has already been recorded");
			}
		}
		var operation = operations.saveAndFlush(new StockOperation(request.requestId(), branch, actor.getId(),
			StockOperation.Kind.SALE, hash, null, request.notes()));
		var prepared = new ArrayList<PreparedLine>();
		var total = BigDecimal.ZERO;
		var cost = BigDecimal.ZERO;
		for (var line : request.lines()) {
			var conversion = quantities.resolve(line.productId(), line.unitId(), line.quantity(), line.measuredBaseQuantity());
			var amount = lineAmount(line);
			var taken = ledger.allocate(operation, line.productId(), conversion.quantity(), currency, saleDate);
			var lineCost = taken.stream().map(SaleStockLedger.Allocation::cost).reduce(BigDecimal.ZERO, BigDecimal::add);
			prepared.add(new PreparedLine(line, conversion, amount, lineCost, taken));
			total = total.add(amount);
			cost = cost.add(lineCost);
		}
		var sale = sales.save(new Sale(operation, actor, request, currency.getCurrencyCode(), total, cost, reference, saleDate));
		int number = 0;
		for (var item : prepared) {
			var line = lines.save(new SaleLine(sale, ++number, item.request(), item.conversion(), item.total(), item.cost()));
			for (var taken : item.allocations()) allocations.save(new SaleAllocation(line, taken));
		}
		audit.save(new AuditEvent(actor.getId(), "SALE_POSTED", "SALE", sale.getId()));
		if (saleDate.isBefore(operation.getPostedAt().atOffset(ZoneOffset.UTC).toLocalDate())) {
			audit.save(new AuditEvent(actor.getId(), "SALE_BACKDATED", "SALE", sale.getId()));
		}
		sales.flush();
		return receipt(sale.getId());
	}

	@Transactional
	public SalesDtos.Receipt voidSale(UUID id, SalesDtos.VoidRequest request) {
		var actor = access.requirePermission(Permission.SALE_VOID);
		var branchId = sales.branchId(id)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sale not found"));
		lock(branchId, false);
		var sale = find(id);
		var hash = PostingFingerprint.of(json, StockOperation.Kind.SALE_VOID, actor.getId(), new VoidIdentity(id, request));
		if (previous(request.requestId(), hash)) return receipt(id);
		if (sale.getVoidOperation() != null) throw conflict("This sale has already been voided");
		var reference = reference(request.refundReference());
		if (sale.getPaymentMethod() != Sale.PaymentMethod.CASH && reference == null) {
			throw invalid("Record the completed M-Pesa/bank refund reference before voiding");
		}
		var reversal = operations.saveAndFlush(new StockOperation(request.requestId(), sale.getBranch(), actor.getId(),
			StockOperation.Kind.SALE_VOID, hash, null, request.reason().trim()));
		ledger.restore(reversal, sale.getId());
		sale.voidSale(reversal, reference);
		audit.save(new AuditEvent(actor.getId(), "SALE_VOIDED", "SALE", sale.getId()));
		sales.flush();
		return receipt(id);
	}

	public SalesDtos.Receipt receipt(UUID id) {
		var actor = access.currentUser();
		var permissions = access.permissions();
		var sale = find(id);
		if (!permissions.contains(Permission.SALES_VIEW) && !permissions.contains(Permission.SALE_VOID)
				&& !(permissions.contains(Permission.SALE_CREATE) && sale.getSellerId().equals(actor.getId()))) {
			throw new AccessDeniedException("Sales viewing permission or ownership is required");
		}
		access.requireBranch(sale.getBranch().getId());
		boolean profit = permissions.contains(Permission.PROFIT_VIEW);
		var receiptCurrency = Currency.getInstance(sale.getCurrency());
		var operation = operations.findById(id).orElseThrow();
		var taken = allocations.forSale(id);
		var items = lines.findBySaleIdOrderByLineNumber(id).stream().map(line -> new SalesDtos.Line(
			line.getId(), line.getLineNumber(), line.getProductId(), line.getProductName(), line.getProductCode(),
			line.getInputUnit(), line.getBaseUnit(), line.getQuantity(), line.getBaseQuantity(),
			line.getConversionNumerator(), line.getConversionDenominator(), line.isMeasured(), line.getUnitPrice(),
			money(line.getTotal(), receiptCurrency), profit ? money(line.getCost(), receiptCurrency) : null,
			taken.stream().filter(item -> item.getLine().getId().equals(line.getId())).map(item ->
				new SalesDtos.Allocation(item.getMovement().getBatch().getId(), item.getMovement().getBatch().getLotCode(),
					item.getQuantity(), profit ? money(item.getCost(), receiptCurrency) : null)).toList())).toList();
		var reversal = sale.getVoidOperation();
		return new SalesDtos.Receipt(id, "PR-" + id.toString().toUpperCase(Locale.ROOT), sale.getBranch().getId(),
			sale.getBranchName(), sale.getSellerId(), sale.getSellerName(), sale.getCustomer(), operation.getPostedAt(), sale.getSaleDate(),
			sale.getCurrency(), sale.getPaymentMethod(), sale.getPaymentReference(), operation.getNotes(),
			reversal == null ? "PAID" : "VOIDED", money(sale.getTotal(), receiptCurrency), profit ? money(sale.getCost(), receiptCurrency) : null,
			profit ? money(sale.getTotal().subtract(sale.getCost()), receiptCurrency) : null,
			reversal == null ? null : reversal.getPostedAt(), reversal == null ? null : reversal.getActorId(),
			reversal == null ? null : reversal.getNotes(), sale.getRefundReference(), items);
	}

	private Sale find(UUID id) {
		return sales.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sale not found"));
	}

	private Branch lock(UUID id, boolean requireActive) {
		access.requireBranch(id);
		var branch = branches.lockForStockUpdate(id)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch not found"));
		if (requireActive && !branch.isActive()) throw conflict("This branch is inactive");
		return branch;
	}

	private boolean previous(UUID id, String hash) {
		var previous = operations.findById(id);
		if (previous.isEmpty()) return false;
		if (!previous.get().getRequestHash().equals(hash)) throw conflict("Request ID already used with different details or actor");
		return true;
	}

	private BigDecimal money(BigDecimal value) {
		return money(value, currency);
	}

	private static LocalDate saleDate(LocalDate date) {
		var today = LocalDate.now(ZoneOffset.UTC);
		if (date == null) return today;
		if (date.isBefore(LocalDate.of(1970, 1, 1)) || date.isAfter(today)) {
			throw invalid("Sale date must be between 1970-01-01 and today (UTC)");
		}
		return date;
	}

	private BigDecimal lineAmount(SalesDtos.LineRequest line) {
		var amount = money(line.quantity().multiply(line.unitPrice()));
		if (amount.signum() <= 0) throw invalid("Each sale line must round to a positive amount in " + currency);
		return amount;
	}

	private static BigDecimal money(BigDecimal value, Currency currency) {
		return value.setScale(currency.getDefaultFractionDigits(), RoundingMode.HALF_UP);
	}

	private static String reference(String value) {
		return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
	}

	private record PreparedLine(SalesDtos.LineRequest request, CatalogueQuantityService.Conversion conversion,
		BigDecimal total, BigDecimal cost, List<SaleStockLedger.Allocation> allocations) {
	}

	private record VoidIdentity(UUID saleId, SalesDtos.VoidRequest request) {
	}

	private static ResponseStatusException invalid(String detail) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
	}

	private static ResponseStatusException conflict(String detail) {
		return new ResponseStatusException(HttpStatus.CONFLICT, detail);
	}
}
