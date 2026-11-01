package com.primeroot.platform.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.primeroot.platform.access.*;
import com.primeroot.platform.audit.AuditEvent;
import com.primeroot.platform.audit.AuditRepository;
import com.primeroot.platform.inventory.InventoryDtos.BranchOption;
import com.primeroot.platform.inventory.PostingFingerprint;
import com.primeroot.platform.sales.Sale;
import com.primeroot.platform.ui.DashboardPeriod;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
@Transactional(readOnly = true)
public class ExpenseService {
	private final AccessService access;
	private final BranchRepository branches;
	private final ExpenseRepository expenses;
	private final ExpenseRequestRepository expenseRequests;
	private final AuditRepository audit;
	private final JsonMapper json;
	private final EntityManager entityManager;
	private final Currency currency;

	public ExpenseService(AccessService access, BranchRepository branches, ExpenseRepository expenses,
			ExpenseRequestRepository expenseRequests,
			AuditRepository audit, JsonMapper json, EntityManager entityManager,
			@Value("${platform.inventory.currency}") String currency) {
		this.access = access;
		this.branches = branches;
		this.expenses = expenses;
		this.expenseRequests = expenseRequests;
		this.audit = audit;
		this.json = json;
		this.entityManager = entityManager;
		this.currency = Currency.getInstance(currency.toUpperCase(Locale.ROOT));
		if (this.currency.getDefaultFractionDigits() < 0) throw new IllegalArgumentException("Expense currency requires minor units");
	}

	public ExpenseDtos.Entry entry() {
		access.requirePermission(Permission.EXPENSE_CREATE);
		return new ExpenseDtos.Entry(currency.getCurrencyCode(), currency.getDefaultFractionDigits(),
			BigDecimal.ONE.movePointLeft(currency.getDefaultFractionDigits()).toPlainString(),
			visible().stream().filter(Branch::isActive).map(this::option).toList(), Arrays.asList(Expense.Category.values()));
	}

	public ExpenseDtos.History history(UUID branchId, Expense.Category category, DashboardPeriod period, int page) {
		access.requirePermission(Permission.EXPENSE_VIEW);
		if (page < 0 || page > 1_000_000) throw invalid("Invalid expense page");
		if (branchId != null) access.requireBranch(branchId);
		var visible = visible();
		var ids = visible.stream().map(Branch::getId).filter(id -> branchId == null || id.equals(branchId)).toList();
		var options = visible.stream().map(this::option).toList();
		if (ids.isEmpty()) return new ExpenseDtos.History(List.of(), page, 0, options);
		var result = expenses.history(ids, period.start(), period.end(), period.from(), period.until(), category, PageRequest.of(page, 50));
		return new ExpenseDtos.History(result.stream().map(this::response).toList(), page, result.getTotalElements(), options);
	}

	@Transactional
	public ExpenseDtos.Detail create(ExpenseDtos.Create request) {
		var actor = access.requirePermission(Permission.EXPENSE_CREATE);
		var branch = lock(request.branchId());
		var hash = PostingFingerprint.of(json, "EXPENSE", actor.getId(), request);
		var previous = expenses.findById(request.requestId());
		if (previous.isPresent()) {
			if (!previous.get().getRequestHash().equals(hash)) throw conflict("Request ID already used with different details or actor");
			return response(previous.get());
		}
		if (expenses.findByVoidRequestId(request.requestId()).isPresent()) throw conflict("Request ID already used for an expense void");
		var reservation = expenseRequests.reserve(request.requestId(), "CREATE", hash);
		if (reservation == ExpenseRequestRepository.Reservation.CONFLICT) {
			throw conflict("Request ID already used for an expense void or different create");
		}
		if (reservation == ExpenseRequestRepository.Reservation.REPLAY) {
			var repeated = expenses.findById(request.requestId());
			if (repeated.isPresent() && repeated.get().getRequestHash().equals(hash)) return response(repeated.get());
			throw conflict("Request ID already used with different details or actor");
		}
		if (!branch.isActive()) throw conflict("Record expenses only in an active branch");
		if (request.paidDate().isBefore(LocalDate.of(1970, 1, 1))
				|| request.paidDate().isAfter(LocalDate.now(ZoneOffset.UTC))) throw invalid("Paid date must be between 1970 and today (UTC)");
		BigDecimal amount;
		try {
			amount = request.amount().setScale(currency.getDefaultFractionDigits(), RoundingMode.UNNECESSARY);
		}
		catch (ArithmeticException exception) {
			throw invalid("Amount must use at most " + currency.getDefaultFractionDigits() + " decimal places for " + currency);
		}
		var reference = request.paymentMethod() == Sale.PaymentMethod.CASH ? null
			: request.paymentReference() == null || request.paymentReference().isBlank() ? null
				: request.paymentReference().trim().toUpperCase(Locale.ROOT);
		if (request.paymentMethod() != Sale.PaymentMethod.CASH && reference == null) throw invalid("Record the completed M-Pesa/bank payment reference");
		if (reference != null && expenses.existsByPaymentMethodAndPaymentReference(request.paymentMethod(), reference)) {
			throw conflict("This expense payment reference has already been recorded");
		}
		var expense = expenses.saveAndFlush(new Expense(branch, actor, request, currency.getCurrencyCode(), amount, reference, hash));
		audit.save(new AuditEvent(actor.getId(), "EXPENSE_POSTED", "EXPENSE", expense.getId()));
		return response(expense);
	}

	@Transactional
	public ExpenseDtos.Detail voidExpense(UUID id, ExpenseDtos.Void request) {
		var actor = access.requirePermission(Permission.EXPENSE_VOID);
		var expense = find(id);
		lock(expense.getBranch().getId());
		entityManager.refresh(expense);
		var hash = PostingFingerprint.of(json, "EXPENSE_VOID", actor.getId(), new VoidIdentity(id, request));
		var previous = expenses.findByVoidRequestId(request.requestId());
		if (previous.isPresent()) {
			if (!previous.get().getVoidHash().equals(hash)) throw conflict("Request ID already used with different details or actor");
			return response(expense);
		}
		if (expenses.existsById(request.requestId())) throw conflict("Request ID already used for an expense");
		var reservation = expenseRequests.reserve(request.requestId(), "VOID", hash);
		if (reservation == ExpenseRequestRepository.Reservation.CONFLICT) {
			throw conflict("Request ID already used for an expense or different void");
		}
		if (reservation == ExpenseRequestRepository.Reservation.REPLAY) {
			var repeated = expenses.findByVoidRequestId(request.requestId());
			if (repeated.isPresent() && repeated.get().getVoidHash().equals(hash)) return response(expense);
			throw conflict("Request ID already used with different details or actor");
		}
		if (expense.getVoidedAt() != null) throw conflict("Expense already voided");
		expense.voidExpense(actor.getId(), request, hash);
		expenses.flush();
		audit.save(new AuditEvent(actor.getId(), "EXPENSE_VOIDED", "EXPENSE", id));
		return response(expense);
	}

	private ExpenseDtos.Detail response(Expense expense) {
		return new ExpenseDtos.Detail(expense.getId(), expense.getBranch().getId(), expense.getBranchName(),
			expense.getActorId(), expense.getActorName(), expense.getPostedAt(), expense.getPaidDate(), expense.getCategory(),
			expense.getDescription(), expense.getPayee(), expense.getCurrency(),
			expense.getAmount().setScale(Currency.getInstance(expense.getCurrency()).getDefaultFractionDigits()),
			expense.getPaymentMethod(), expense.getPaymentReference(), expense.getVoidedAt(), expense.getVoidedBy(),
			expense.getVoidReason(), expense.getVoidedAt() == null && access.permissions().contains(Permission.EXPENSE_VOID));
	}

	private Branch lock(UUID id) {
		access.requireBranch(id);
		return branches.lockForStockUpdate(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch not found"));
	}

	private Expense find(UUID id) {
		return expenses.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Expense not found"));
	}

	private List<Branch> visible() {
		var actor = access.currentUser();
		return actor.isAllBranches() ? branches.findAllByOrderByCode() : branches.findAccessibleBranches(actor.getId());
	}

	private BranchOption option(Branch branch) { return new BranchOption(branch.getId(), branch.getName(), branch.isActive()); }
	private record VoidIdentity(UUID expenseId, ExpenseDtos.Void request) {}
	private static ResponseStatusException invalid(String detail) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail); }
	private static ResponseStatusException conflict(String detail) { return new ResponseStatusException(HttpStatus.CONFLICT, detail); }
}
