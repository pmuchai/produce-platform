package com.primeroot.platform.ui;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.primeroot.platform.access.BranchService;
import com.primeroot.platform.access.Permission;
import com.primeroot.platform.access.StaffService;
import com.primeroot.platform.audit.AuditService;
import com.primeroot.platform.catalogue.CatalogueService;
import com.primeroot.platform.inventory.InventoryService;
import com.primeroot.platform.inventory.TransferService;
import com.primeroot.platform.inventory.StockTransfer;
import com.primeroot.platform.sales.SalesService;
import com.primeroot.platform.sales.SalesReportService;
import com.primeroot.platform.sales.SalesReports;
import com.primeroot.platform.finance.Expense;
import com.primeroot.platform.finance.ExpenseService;
import com.primeroot.platform.finance.NetProfitService;
import com.primeroot.platform.whatsapp.WhatsAppLinkService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class WorkspaceController {
	private final StaffService staff;
	private final BranchService branches;
	private final CatalogueService catalogue;
	private final AuditService audit;
	private final InventoryService inventory;
	private final SalesService sales;
	private final SalesReportService salesReports;
	private final TransferService transfers;
	private final ExpenseService expenses;
	private final NetProfitService netProfit;
	private final ObjectProvider<WhatsAppLinkService> whatsapp;
	private final String businessCurrency;

	public WorkspaceController(StaffService staff, BranchService branches, CatalogueService catalogue,
			AuditService audit, InventoryService inventory, SalesService sales, SalesReportService salesReports,
			TransferService transfers, ExpenseService expenses, NetProfitService netProfit,
			ObjectProvider<WhatsAppLinkService> whatsapp,
			@org.springframework.beans.factory.annotation.Value("${platform.inventory.currency}") String businessCurrency) {
		this.staff = staff;
		this.branches = branches;
		this.catalogue = catalogue;
		this.audit = audit;
		this.inventory = inventory;
		this.sales = sales;
		this.salesReports = salesReports;
		this.transfers = transfers;
		this.expenses = expenses;
		this.netProfit = netProfit;
		this.whatsapp = whatsapp;
		this.businessCurrency = java.util.Currency.getInstance(businessCurrency.toUpperCase(java.util.Locale.ROOT)).getCurrencyCode();
	}

	private void workspace(Model model, String page) {
		var current = staff.me();
		var permissions = current.permissions();
		boolean global = current.staff().allBranches();
		model.addAttribute("current", current);
		model.addAttribute("page", page);
		model.addAttribute("navGroup", switch (page) {
			case "receipts", "stock" -> "stock";
			case "stock-report", "movement-report", "audit-report", "sales-report", "net-profit" -> "reports";
			case "sale", "sale-edit", "receipt" -> "sales";
			case "catalogue", "branches", "configurations" -> "configurations";
			default -> page;
		});
		model.addAttribute("canCatalogue", permissions.contains(Permission.CATALOGUE_VIEW));
		model.addAttribute("manageCatalogue", global && permissions.contains(Permission.CATALOGUE_MANAGE));
		model.addAttribute("canBranches", permissions.contains(Permission.BRANCH_VIEW));
		model.addAttribute("manageBranches", permissions.contains(Permission.BRANCH_MANAGE));
		model.addAttribute("createBranch", global && permissions.contains(Permission.BRANCH_MANAGE));
		model.addAttribute("canUsers", global && permissions.contains(Permission.USER_VIEW));
		model.addAttribute("manageUsers", global && permissions.contains(Permission.USER_MANAGE));
		model.addAttribute("canAudit", global && permissions.contains(Permission.AUDIT_VIEW));
		model.addAttribute("canStock", permissions.contains(Permission.STOCK_VIEW));
		model.addAttribute("canTransfers", permissions.contains(Permission.TRANSFER_VIEW));
		model.addAttribute("dispatchTransfer", permissions.contains(Permission.TRANSFER_DISPATCH));
		model.addAttribute("receiveStock", permissions.contains(Permission.STOCK_RECEIVE));
		model.addAttribute("adjustStock", permissions.contains(Permission.STOCK_ADJUST));
		model.addAttribute("spoilStock", permissions.contains(Permission.STOCK_SPOILAGE));
		model.addAttribute("canSales", permissions.contains(Permission.SALE_CREATE) || permissions.contains(Permission.SALES_VIEW));
		model.addAttribute("canWhatsApp", whatsapp.getIfAvailable() != null);
		model.addAttribute("createSale", staff.canCreateSale());
		model.addAttribute("editSale", permissions.contains(Permission.SALE_EDIT));
		model.addAttribute("voidSale", permissions.contains(Permission.SALE_VOID));
		model.addAttribute("canProfit", permissions.contains(Permission.PROFIT_VIEW));
		model.addAttribute("canExpenses", permissions.contains(Permission.EXPENSE_VIEW));
		model.addAttribute("createExpense", permissions.contains(Permission.EXPENSE_CREATE));
		model.addAttribute("salesReportKinds", java.util.Arrays.stream(SalesReports.Kind.values()).filter(kind ->
			kind.isFinancial() ? permissions.contains(Permission.PROFIT_VIEW) : kind == SalesReports.Kind.LOSSES
				? permissions.contains(Permission.STOCK_VIEW) : permissions.contains(Permission.SALES_VIEW)
					|| permissions.contains(Permission.SALE_CREATE)).toList());
		model.addAttribute("today", LocalDate.now(ZoneOffset.UTC));
	}

	@GetMapping("/ui/whatsapp")
	public String whatsapp(Model model) {
		var links = whatsapp.getIfAvailable();
		if (links == null) throw new org.springframework.web.server.ResponseStatusException(
			org.springframework.http.HttpStatus.NOT_FOUND, "WhatsApp integration is not enabled");
		workspace(model, "whatsapp");
		model.addAttribute("whatsapp", links.status());
		return "whatsapp";
	}

	@GetMapping("/home")
	public String home(Model model,
			@RequestParam(required = false) String month,
			@RequestParam(required = false) String currency,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
		workspace(model, "dashboard");
		if (Boolean.TRUE.equals(model.getAttribute("createSale"))) return "redirect:/ui/sales";
		var window = DashboardWindow.resolve(month, start, end);
		var period = window.period();
		model.addAttribute("window", window);
		model.addAttribute("period", period);
		var selectedMonth = java.time.YearMonth.parse(window.month());
		model.addAttribute("previousMonth", selectedMonth.isAfter(java.time.YearMonth.of(1970, 1)) ? selectedMonth.minusMonths(1) : null);
		model.addAttribute("nextMonth", selectedMonth.isBefore(java.time.YearMonth.now(ZoneOffset.UTC)) ? selectedMonth.plusMonths(1) : null);
		com.primeroot.platform.finance.NetProfitReport currentProfit = null;
		com.primeroot.platform.finance.NetProfitReport priorProfit = null;
		com.primeroot.platform.sales.SalesReports.Report currentSales = null;
		com.primeroot.platform.sales.SalesReports.Report priorSales = null;
		var charts = java.util.List.<DashboardCharts.CurrencyChart>of();
		if (Boolean.TRUE.equals(model.getAttribute("canProfit"))) {
			currentProfit = netProfit.report(null, period, 0);
			priorProfit = netProfit.report(null, window.previous(), 0);
			model.addAttribute("netProfit", currentProfit);
			model.addAttribute("previousProfit", priorProfit);
		}
		if (Boolean.TRUE.equals(model.getAttribute("canSales"))) {
			currentSales = salesReports.chart(SalesReports.Kind.DAILY, period);
			priorSales = salesReports.chart(SalesReports.Kind.DAILY, window.previous());
			model.addAttribute("sales", currentSales);
			charts = DashboardCharts.build(window, currentSales, priorSales, salesReports.chart(SalesReports.Kind.STAFF, period));
		}
		var overview = DashboardSummary.build(currency, businessCurrency, charts, currentSales, priorSales, currentProfit, priorProfit);
		model.addAttribute("overview", overview);
		if (currentSales != null) {
			model.addAttribute("soldQuantities", DashboardSummary.quantities(salesReports.quantities(period), overview.currency()));
		}

		if (Boolean.TRUE.equals(model.getAttribute("canStock"))) {
			var snapshot = inventory.snapshot(null, period, window.previous(), 0);
			model.addAttribute("inventory", snapshot);
			var stock = DashboardSummary.stock(snapshot.balances());
			model.addAttribute("liveStock", stock.stream().limit(3).toList());
			model.addAttribute("stockProducts", stock.size());
		}
		if (Boolean.TRUE.equals(model.getAttribute("canBranches"))) {
			model.addAttribute("branches", branches.list());
		}
		if (Boolean.TRUE.equals(model.getAttribute("canUsers"))) {
			model.addAttribute("users", staff.list());
		}
		if (Boolean.TRUE.equals(model.getAttribute("canCatalogue"))) {
			model.addAttribute("catalogue", catalogue.list());
		}
		if (Boolean.TRUE.equals(model.getAttribute("canAudit"))) {
			long count = audit.count(period.from(), period.until());
			long previous = audit.count(window.previous().from(), window.previous().until());
			model.addAttribute("auditCount", count);
			model.addAttribute("auditDelta", count - previous);
			model.addAttribute("previousCount", previous);
		}
		return "dashboard";
	}

	@GetMapping("/ui/expenses")
	public String expenses(Model model, @RequestParam(required = false) UUID branchId,
			@RequestParam(required = false) Expense.Category category,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
			@RequestParam(defaultValue = "0") int page) {
		var period = DashboardPeriod.resolve(start, end);
		model.addAttribute("history", expenses.history(branchId, category, period, page));
		model.addAttribute("period", period);
		model.addAttribute("selectedBranch", branchId);
		model.addAttribute("selectedCategory", category);
		model.addAttribute("categories", Expense.Category.values());
		workspace(model, "expenses");
		if (Boolean.TRUE.equals(model.getAttribute("createExpense"))) model.addAttribute("entry", expenses.entry());
		return "expenses";
	}

	@GetMapping("/ui/reports/net-profit")
	public String netProfit(Model model, @RequestParam(required = false) UUID branchId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
			@RequestParam(defaultValue = "0") int page) {
		var period = DashboardPeriod.resolve(start, end);
		model.addAttribute("report", netProfit.report(branchId, period, page));
		model.addAttribute("period", period);
		model.addAttribute("selectedBranch", branchId);
		workspace(model, "net-profit");
		return "net-profit";
	}

	@GetMapping("/ui/transfers")
	public String transfers(Model model, @RequestParam(required = false) StockTransfer.Status status,
			@RequestParam(defaultValue = "0") int page) {
		model.addAttribute("history", transfers.history(status, page));
		model.addAttribute("selectedStatus", status);
		workspace(model, "transfers");
		if (Boolean.TRUE.equals(model.getAttribute("dispatchTransfer"))) model.addAttribute("entry", transfers.entry());
		return "transfers";
	}

	@GetMapping("/ui/transfers/{id}")
	public String transfer(Model model, @org.springframework.web.bind.annotation.PathVariable UUID id) {
		model.addAttribute("detail", transfers.detail(id));
		workspace(model, "transfers");
		return "transfer";
	}

	@GetMapping("/ui/sales")
	public String sale(Model model) {
		model.addAttribute("entry", sales.entry());
		workspace(model, "sale");
		return "sale";
	}

	@GetMapping("/ui/sales/{id}")
	public String receipt(Model model, @org.springframework.web.bind.annotation.PathVariable UUID id) {
		model.addAttribute("receipt", sales.receipt(id));
		workspace(model, "receipt");
		return "sale-receipt";
	}

	@GetMapping("/ui/sales/edit")
	public String editSales(Model model, @RequestParam(required = false) LocalDate week,
			@RequestParam(required = false) UUID branchId, @RequestParam(defaultValue = "0") int page) {
		model.addAttribute("editing", sales.editPage(week, branchId, page));
		model.addAttribute("selectedBranch", branchId);
		workspace(model, "sale-edit");
		return "sale-edit";
	}

	@GetMapping({"/ui/reports/sales", "/ui/reports/daily-sales", "/ui/reports/product-sales",
		"/ui/reports/branch-sales", "/ui/reports/staff-performance", "/ui/reports/customer-sales",
		"/ui/reports/payments", "/ui/reports/profit", "/ui/reports/stock-valuation", "/ui/reports/stock-losses"})
	public String salesReport(Model model, jakarta.servlet.http.HttpServletRequest request,
			@RequestParam(required = false) UUID branchId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
			@RequestParam(defaultValue = "0") int page) {
		var period = DashboardPeriod.resolve(start, end);
		var kind = SalesReports.Kind.from(request.getServletPath().substring("/ui/reports/".length()));
		model.addAttribute("report", salesReports.report(kind, branchId, period, page));
		model.addAttribute("reportPath", request.getServletPath());
		model.addAttribute("selectedBranch", branchId);
		model.addAttribute("period", period);
		workspace(model, "sales-report");
		return "sales-report";
	}

	@GetMapping("/ui/reports/staff-product-sales")
	public String staffProductSales(Model model, @RequestParam(required = false) UUID branchId,
			@RequestParam(required = false) UUID staffId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "false") boolean print) {
		var period = DashboardPeriod.resolve(start, end);
		var report = salesReports.staffProducts(branchId, staffId, period, page, print);
		model.addAttribute("report", report);
		model.addAttribute("selectedBranch", branchId);
		model.addAttribute("selectedStaff", staffId);
		model.addAttribute("missingStaffOption", staffId != null && report.staff().stream()
			.noneMatch(option -> option.id().equals(staffId)));
		model.addAttribute("branchLabel", branchId == null ? "All accessible branches" : report.branches().stream()
			.filter(branch -> branch.id().equals(branchId)).map(branch -> branch.name()).findFirst().orElse("Selected branch"));
		model.addAttribute("staffLabel", staffId == null ? (report.ownSalesOnly() ? "Your own sales" : "All accessible staff")
			: report.staff().stream().filter(option -> option.id().equals(staffId))
				.map(option -> option.name() + " (" + option.username() + ")").findFirst().orElse("Selected staff (no activity)"));
		model.addAttribute("period", period);
		model.addAttribute("printView", print);
		workspace(model, "sales-report");
		return "staff-product-sales";
	}

	@GetMapping({"/ui/stock-entries", "/ui/stock", "/ui/reports/stock", "/ui/reports/stock-movements"})
	public String inventory(Model model, jakarta.servlet.http.HttpServletRequest request,
			@RequestParam(required = false) UUID branchId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "0") int batchPage,
			@RequestParam(required = false) String batchSearch) {
		var period = DashboardPeriod.resolve(start, end);
		model.addAttribute("inventory", inventory.snapshot(branchId, period, page));
		var path = request.getServletPath();
		var workspacePage = switch (path) {
			case "/ui/stock-entries" -> "receipts";
			case "/ui/reports/stock" -> "stock-report";
			case "/ui/reports/stock-movements" -> "movement-report";
			default -> "stock";
		};
		if (!workspacePage.equals("movement-report")) {
			model.addAttribute("batchPage", inventory.batchPage(branchId, batchSearch, batchPage));
		}
		workspace(model, workspacePage);
		model.addAttribute("inventoryPath", path);
		model.addAttribute("inventoryTitle", switch (workspacePage) {
			case "receipts" -> "Receive stock";
			case "stock-report" -> "Stock levels report";
			case "movement-report" -> "Stock movements report";
			default -> "Stock Movement";
		});
		model.addAttribute("period", period);
		model.addAttribute("selectedBranch", branchId);
		return "inventory";
	}

	@GetMapping("/ui/catalogue")
	public String catalogue(Model model) {
		var snapshot = catalogue.list();
		workspace(model, "catalogue");
		model.addAttribute("catalogue", snapshot);
		Map<UUID, String> labels = new LinkedHashMap<>();
		snapshot.types().forEach(item -> labels.put(item.id(), item.name()));
		snapshot.classifications().forEach(item -> labels.put(item.id(), item.name()));
		snapshot.units().forEach(item -> labels.put(item.id(), item.code()));
		snapshot.products().forEach(item -> labels.put(item.id(), item.name()));
		model.addAttribute("labels", labels);
		return "catalogue";
	}

	@GetMapping("/ui/branches")
	public String branches(Model model) {
		model.addAttribute("branches", branches.list());
		workspace(model, "branches");
		return "branches";
	}

	@GetMapping("/ui/users")
	public String users(Model model) {
		model.addAttribute("users", staff.list());
		model.addAttribute("accessRequests", staff.pendingRequests());
		workspace(model, "users");
		if (Boolean.TRUE.equals(model.getAttribute("canBranches"))) {
			var visibleBranches = branches.list();
			model.addAttribute("branches", visibleBranches);
			Map<UUID, String> labels = new LinkedHashMap<>();
			visibleBranches.forEach(branch -> labels.put(branch.id(),
				branch.name() + (branch.active() ? "" : " (inactive)")));
			model.addAttribute("branchLabels", labels);
		}
		return "users";
	}

	@GetMapping({"/ui/reports", "/ui/reports/audit"})
	public String reports(Model model,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
		var period = DashboardPeriod.resolve(start, end);
		model.addAttribute("events", audit.list(period.from(), period.until()));
		model.addAttribute("eventCount", audit.count(period.from(), period.until()));
		model.addAttribute("period", period);
		workspace(model, "audit-report");
		return "reports";
	}

	@GetMapping("/ui/configurations")
	public String configurations(Model model) {
		workspace(model, "configurations");
		return "configurations";
	}
}
