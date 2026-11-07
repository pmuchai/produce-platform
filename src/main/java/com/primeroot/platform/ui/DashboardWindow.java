package com.primeroot.platform.ui;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public record DashboardWindow(String month, DashboardPeriod period, DashboardPeriod previous, String comparison) {
	public static DashboardWindow resolve(String month, LocalDate start, LocalDate end) {
		if (month == null && (start != null || end != null)) {
			var period = DashboardPeriod.resolve(start, end);
			var previousStart = period.previousFrom().atOffset(ZoneOffset.UTC).toLocalDate();
			return new DashboardWindow(YearMonth.from(period.start()).toString(), period,
				new DashboardPeriod(previousStart, period.start().minusDays(1)), "previous equal-length interval");
		}
		if (start != null || end != null) throw invalidMonth();
		var today = LocalDate.now(ZoneOffset.UTC);
		YearMonth selected;
		try {
			selected = month == null ? YearMonth.from(today) : YearMonth.parse(month);
		}
		catch (DateTimeParseException ex) {
			throw invalidMonth();
		}
		if (selected.isBefore(YearMonth.of(1970, 1)) || selected.isAfter(YearMonth.from(today))) throw invalidMonth();
		boolean current = selected.equals(YearMonth.from(today));
		var previousMonth = selected.minusMonths(1);
		var previousEnd = current
			? previousMonth.atDay(Math.min(today.getDayOfMonth(), previousMonth.lengthOfMonth()))
			: previousMonth.atEndOfMonth();
		return new DashboardWindow(selected.toString(),
			new DashboardPeriod(selected.atDay(1), current ? today : selected.atEndOfMonth()),
			new DashboardPeriod(previousMonth.atDay(1), previousEnd),
			current ? "same elapsed days of previous month" : "previous calendar month");
	}

	private static ResponseStatusException invalidMonth() {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST,
			"Select a month from January 1970 through the current UTC month; do not combine month and date filters");
	}
}
