package com.primeroot.platform.ui;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public record DashboardPeriod(LocalDate start, LocalDate end) {
	public static DashboardPeriod resolve(LocalDate start, LocalDate end) {
		var today = LocalDate.now(ZoneOffset.UTC);
		if (start == null && end == null) {
			start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
			end = today;
		}
		if (start == null || end == null || start.isBefore(LocalDate.of(1970, 1, 1))
				|| end.isBefore(start) || end.isAfter(today) || ChronoUnit.DAYS.between(start, end) >= 366) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
				"Supply both dates in UTC, start before or equal to end, no future dates, and at most 366 days");
		}
		return new DashboardPeriod(start, end);
	}

	public Instant from() { return start.atStartOfDay().toInstant(ZoneOffset.UTC); }
	public Instant until() { return end.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC); }
	public Instant previousFrom() {
		return start.minusDays(ChronoUnit.DAYS.between(start, end) + 1).atStartOfDay().toInstant(ZoneOffset.UTC);
	}
}
