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

	public WorkspaceController(StaffService staff, BranchService branches, CatalogueService catalogue, AuditService audit) {
		this.staff = staff;
		this.branches = branches;
		this.catalogue = catalogue;
		this.audit = audit;
	}

	private void workspace(Model model, String page) {
		var current = staff.me();
		var permissions = current.permissions();
		boolean global = current.staff().allBranches();
		model.addAttribute("current", current);
		model.addAttribute("page", page);
		model.addAttribute("canCatalogue", permissions.contains(Permission.CATALOGUE_VIEW));
		model.addAttribute("manageCatalogue", global && permissions.contains(Permission.CATALOGUE_MANAGE));
		model.addAttribute("canBranches", permissions.contains(Permission.BRANCH_VIEW));
		model.addAttribute("canUsers", global && permissions.contains(Permission.USER_VIEW));
		model.addAttribute("canAudit", global && permissions.contains(Permission.AUDIT_VIEW));
		model.addAttribute("today", LocalDate.now(ZoneOffset.UTC));
	}

	@GetMapping("/home")
	public String home(Model model,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
		workspace(model, "dashboard");
		var period = DashboardPeriod.resolve(start, end);
		model.addAttribute("period", period);
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

	@GetMapping("/ui/catalogue")
	public String catalogue(Model model) {
		var snapshot = catalogue.list();
		workspace(model, "configurations");
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
		workspace(model, "configurations");
		return "branches";
	}

	@GetMapping("/ui/users")
	public String users(Model model) {
		model.addAttribute("users", staff.list());
		workspace(model, "users");
		return "users";
	}

	@GetMapping("/ui/reports")
	public String reports(Model model,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
		var period = DashboardPeriod.resolve(start, end);
		model.addAttribute("events", audit.list(period.from(), period.until()));
		model.addAttribute("eventCount", audit.count(period.from(), period.until()));
		model.addAttribute("period", period);
		workspace(model, "reports");
		return "reports";
	}

	@GetMapping("/ui/configurations")
	public String configurations(Model model) {
		workspace(model, "configurations");
		return "configurations";
	}
}
