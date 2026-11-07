package com.primeroot.platform.whatsapp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.primeroot.platform.access.AccessService;
import com.primeroot.platform.audit.AuditEvent;
import com.primeroot.platform.audit.AuditRepository;
import com.primeroot.platform.sales.SalesReportService;
import com.primeroot.platform.sales.SalesReports;
import com.primeroot.platform.ui.DashboardPeriod;
import org.springframework.stereotype.Service;

@Service
public class SalesSummaryTool {
	public static final String NAME = "get_sales_summary";
	private final SalesReportService reports;
	private final AccessService access;
	private final AuditRepository audit;

	public SalesSummaryTool(SalesReportService reports, AccessService access, AuditRepository audit) {
		this.reports = reports;
		this.access = access;
		this.audit = audit;
	}

	public record Total(String currency, long sales, long refunds,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal netSales) {}
	public record Summary(LocalDate startDate, LocalDate endDate, String timezone, String scope,
			boolean ownSalesOnly, List<Total> totals) {}

	public Summary execute(LocalDate start, LocalDate end, UUID branchId) {
		if (start == null && end == null) start = end = LocalDate.now(ZoneOffset.UTC);
		var period = DashboardPeriod.resolve(start, end);
		var report = reports.report(SalesReports.Kind.DAILY, branchId, period, 0);
		var visible = report.branches().stream().filter(branch -> branchId == null || branch.id().equals(branchId)).toList();
		var scope = visible.size() == 1 ? visible.getFirst().name() : "All authorized branches";
		var totals = report.totals().stream().map(total ->
			new Total(total.currency(), total.receipts(), total.voids(), total.revenue())).toList();
		var user = access.currentUser();
		audit.save(new AuditEvent(user.getId(), "SALES_SUMMARY_QUERIED", "USER", user.getId()));
		return new Summary(start, end, "UTC", scope, report.ownSalesOnly(), totals);
	}

	public static String reply(Summary summary) {
		var text = new StringBuilder("Prime Root sales summary\n")
			.append(summary.startDate()).append(summary.startDate().equals(summary.endDate()) ? "" : " to " + summary.endDate())
			.append(" (UTC)\n").append(summary.scope()).append(summary.ownSalesOnly() ? " / Your sales only" : "")
			.append('\n');
		if (summary.totals().isEmpty()) return text.append("No sales or refunds recorded in this interval.").toString();
		for (var total : summary.totals()) {
			text.append("Net sales: ").append(total.currency()).append(' ').append(total.netSales().toPlainString())
				.append(" | Sales: ").append(total.sales()).append(" | Refunds: ").append(total.refunds()).append('\n');
		}
		return text.append("Net sales = recorded sales less refunds in this interval; not profit.").toString();
	}
}
