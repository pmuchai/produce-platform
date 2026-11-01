package com.primeroot.platform.finance;

import java.time.LocalDate;
import java.util.UUID;

import com.primeroot.platform.ui.DashboardPeriod;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/expenses")
public class ExpenseController {
	private final ExpenseService expenses;
	public ExpenseController(ExpenseService expenses) { this.expenses = expenses; }

	@GetMapping("/entry")
	public ExpenseDtos.Entry entry() { return expenses.entry(); }

	@GetMapping
	public ExpenseDtos.History history(@RequestParam(required = false) UUID branchId,
			@RequestParam(required = false) Expense.Category category,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
			@RequestParam(defaultValue = "0") int page) {
		return expenses.history(branchId, category, DashboardPeriod.resolve(start, end), page);
	}

	@PostMapping @ResponseStatus(HttpStatus.CREATED)
	public ExpenseDtos.Detail create(@Valid @RequestBody ExpenseDtos.Create request) { return expenses.create(request); }

	@PostMapping("/{id}/void")
	public ExpenseDtos.Detail voidExpense(@PathVariable UUID id, @Valid @RequestBody ExpenseDtos.Void request) {
		return expenses.voidExpense(id, request);
	}
}
