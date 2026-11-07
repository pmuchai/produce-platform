package com.primeroot.platform;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;

import com.primeroot.platform.ui.DashboardWindow;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DashboardWindowTests {
	@Test
	void defaultsToMonthToDateAndComparesEquivalentElapsedDaysAcrossMonthLengths() {
		var today = LocalDate.now(ZoneOffset.UTC);
		var prior = YearMonth.from(today).minusMonths(1);
		var window = DashboardWindow.resolve(null, null, null);
		assertThat(window.period().start()).isEqualTo(today.withDayOfMonth(1));
		assertThat(window.period().end()).isEqualTo(today);
		assertThat(window.previous().start()).isEqualTo(prior.atDay(1));
		assertThat(window.previous().end()).isEqualTo(prior.atDay(Math.min(today.getDayOfMonth(), prior.lengthOfMonth())));
	}

	@Test
	void completedLeapMonthUsesFullPreviousCalendarMonthNotAnEqualLengthInterval() {
		var window = DashboardWindow.resolve("2024-02", null, null);
		assertThat(window.period().start()).isEqualTo(LocalDate.of(2024, 2, 1));
		assertThat(window.period().end()).isEqualTo(LocalDate.of(2024, 2, 29));
		assertThat(window.previous().start()).isEqualTo(LocalDate.of(2024, 1, 1));
		assertThat(window.previous().end()).isEqualTo(LocalDate.of(2024, 1, 31));
	}

	@Test
	void rejectsMalformedFutureAndConflictingMonthFilters() {
		for (var month : new String[] { "", "2024-2", "2024-13", "1969-12",
				YearMonth.now(ZoneOffset.UTC).plusMonths(1).toString() }) {
			assertThatThrownBy(() -> DashboardWindow.resolve(month, null, null))
				.isInstanceOf(ResponseStatusException.class);
		}
		assertThatThrownBy(() -> DashboardWindow.resolve("2024-02", LocalDate.of(2024, 2, 1), null))
			.isInstanceOf(ResponseStatusException.class);
	}
}
