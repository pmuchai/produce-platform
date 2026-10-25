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
import com.primeroot.platform.sales.SalesService;
import com.primeroot.platform.sales.SalesReportService;
import com.primeroot.platform.sales.SalesReports;
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

	public WorkspaceController(StaffService staff, BranchService branches, CatalogueService catalogue,
			AuditService audit, InventoryService inventory, SalesService sales, SalesReportService salesReports) {
		this.staff = staff;
		this.branches = branches;
		this.catalogue = catalogue;
		this.audit = audit;
		this.inventory = inventory;
		this.sales = sales;
		this.salesReports = salesReports;
	}

	private void workspace(Model model, String page) {
		var current = staff.me();
		var permissions = current.permissions();
		boolean global = current.staff().allBranches();
		model.addAttribute("current", current);
		model.addAttribute("page", page);
		model.addAttribute("navGroup", switch (page) {
			case "receipts", "stock" -> "stock";
			case "stock-report", "movement-report", "audit-report", "sales-report" -> "reports";
			case "sale", "receipt" -> "sales";
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
		model.addAttribute("receiveStock", permissions.contains(Permission.STOCK_RECEIVE));
		model.addAttribute("adjustStock", permissions.contains(Permission.STOCK_ADJUST));
		model.addAttribute("spoilStock", permissions.contains(Permission.STOCK_SPOILAGE));
		model.addAttribute("canSales", permissions.contains(Permission.SALE_CREATE) || permissions.contains(Permission.SALES_VIEW));
		model.addAttribute("createSale", permissions.contains(Permission.SALE_CREATE)
			&& !global && current.staff().branchIds().size() == 1);
		model.addAttribute("voidSale", permissions.contains(Permission.SALE_VOID));
		model.addAttribute("canProfit", permissions.contains(Permission.PROFIT_VIEW));
		model.addAttribute("salesReportKinds", java.util.Arrays.stream(SalesReports.Kind.values()).filter(kind ->
			kind.isFinancial() ? permissions.contains(Permission.PROFIT_VIEW) : kind == SalesReports.Kind.LOSSES
				? permissions.contains(Permission.STOCK_VIEW) : permissions.contains(Permission.SALES_VIEW)
					|| permissions.contains(Permission.SALE_CREATE)).toList());
		model.addAttribute("today", LocalDate.now(ZoneOffset.UTC));
	}

	@GetMapping("/home")
	public String home(Model model,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
		workspace(model, "dashboard");
		var period = DashboardPeriod.resolve(start, end);
		model.addAttribute("period", period);
		if (Boolean.TRUE.equals(model.getAttribute("canSales"))) {
			var report = salesReports.report(SalesReports.Kind.SALES, null, period, 0);
			model.addAttribute("sales", report);
			Map<String, java.math.BigDecimal> previous = new LinkedHashMap<>();
			report.previous().forEach(total -> previous.put(total.currency(), total.revenue()));
			Map<String, java.math.BigDecimal> current = new LinkedHashMap<>();
			report.totals().forEach(total -> current.put(total.currency(), total.revenue()));
			previous.keySet().forEach(code -> current.putIfAbsent(code, java.math.BigDecimal.ZERO));
			model.addAttribute("salesTrends", current.entrySet().stream().map(entry ->
				new SalesTrend(entry.getKey(), entry.getValue(),
					entry.getValue().subtract(previous.getOrDefault(entry.getKey(), java.math.BigDecimal.ZERO)))).toList());
		}
		if (Boolean.TRUE.equals(model.getAttribute("canStock"))) {
			model.addAttribute("inventory", inventory.snapshot(null, period, 0));
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
			long previous = audit.count(period.previousFrom(), period.from());
			model.addAttribute("auditCount", count);
			model.addAttribute("auditDelta", count - previous);
			model.addAttribute("previousCount", previous);
		}
		return "dashboard";
	}

	private record SalesTrend(String currency, java.math.BigDecimal revenue, java.math.BigDecimal change) {
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
