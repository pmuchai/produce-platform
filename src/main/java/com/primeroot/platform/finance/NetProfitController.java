package com.primeroot.platform.finance;

import java.time.LocalDate;
import java.util.UUID;

import com.primeroot.platform.ui.DashboardPeriod;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/finance/reports/net-profit")
public class NetProfitController {
	private final NetProfitService reports;
	public NetProfitController(NetProfitService reports) { this.reports = reports; }

	@GetMapping
	public NetProfitReport report(@RequestParam(required = false) UUID branchId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
			@RequestParam(defaultValue = "0") int page) {
		return reports.report(branchId, DashboardPeriod.resolve(start, end), page);
	}
}
