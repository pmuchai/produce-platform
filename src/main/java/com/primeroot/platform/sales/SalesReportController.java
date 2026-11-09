package com.primeroot.platform.sales;

import java.time.LocalDate;
import java.util.UUID;
import com.primeroot.platform.ui.DashboardPeriod;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sales/reports")
public class SalesReportController {
	private final SalesReportService reports;
	public SalesReportController(SalesReportService reports) { this.reports = reports; }

	@GetMapping("/staff-product-sales")
	public SalesReports.StaffProductReport staffProducts(@RequestParam(required = false) UUID branchId,
			@RequestParam(required = false) UUID staffId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
			@RequestParam(defaultValue = "0") int page) {
		return reports.staffProducts(branchId, staffId, DashboardPeriod.resolve(start, end), page, false);
	}

	@GetMapping("/{kind}")
	public SalesReports.Report report(@PathVariable String kind, @RequestParam(required = false) UUID branchId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
			@RequestParam(defaultValue = "0") int page) {
		return reports.report(SalesReports.Kind.from(kind), branchId, DashboardPeriod.resolve(start, end), page);
	}
}
