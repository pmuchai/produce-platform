package com.primeroot.platform.inventory;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import com.primeroot.platform.access.AccessService;
import com.primeroot.platform.access.BranchRepository;
import com.primeroot.platform.access.Permission;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReceiptReferenceService {
	private final AccessService access;
	private final BranchRepository branches;
	private final JdbcTemplate jdbc;

	public ReceiptReferenceService(AccessService access, BranchRepository branches, JdbcTemplate jdbc) {
		this.access = access;
		this.branches = branches;
		this.jdbc = jdbc;
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public InventoryDtos.ReceiptReference reserve(InventoryDtos.ReferenceRequest request) {
		var actor = access.requirePermission(Permission.STOCK_RECEIVE);
		access.requireBranch(request.branchId());
		var branch = branches.lockForStockUpdate(request.branchId())
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch not found"));
		if (!branch.isActive() || request.receivedDate().isBefore(LocalDate.of(1970, 1, 1))
				|| request.receivedDate().isAfter(LocalDate.now(ZoneOffset.UTC))) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose an active branch and a valid received date");
		}
		var previous = jdbc.query("SELECT * FROM receipt_reference_reservations WHERE id = ?",
			(rs, index) -> {
				if (!actor.getId().equals(rs.getObject("actor_id", UUID.class))
						|| !request.branchId().equals(rs.getObject("branch_id", UUID.class))
						|| !request.receivedDate().equals(rs.getObject("received_date", LocalDate.class))) {
					throw new ResponseStatusException(HttpStatus.CONFLICT, "Reference request has already been used");
				}
				return new InventoryDtos.ReceiptReference(rs.getString("reference"));
			}, request.requestId());
		if (!previous.isEmpty()) return previous.getFirst();
		long sequence = jdbc.queryForObject("""
			SELECT COALESCE(MAX(sequence_number), 0) FROM receipt_reference_reservations
			WHERE branch_id = ? AND received_date = ?
			""", Long.class, branch.getId(), request.receivedDate());
		String reference;
		do {
			if (++sequence > 999_999_999) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "Daily delivery reference limit reached");
			}
			reference = "DEL-" + branch.getCode() + "-" + request.receivedDate().format(DateTimeFormatter.BASIC_ISO_DATE)
				+ "-" + String.format(java.util.Locale.ROOT, "%03d", sequence);
		} while (jdbc.queryForObject("""
			SELECT (SELECT COUNT(*) FROM stock_operations WHERE source = ?)
				+ (SELECT COUNT(*) FROM receipt_reference_reservations WHERE reference = ?)
			""", Long.class, reference, reference) > 0);
		jdbc.update("""
			INSERT INTO receipt_reference_reservations(id, branch_id, actor_id, received_date, sequence_number, reference)
			VALUES (?, ?, ?, ?, ?, ?)
			""", request.requestId(), branch.getId(), actor.getId(), request.receivedDate(), sequence, reference);
		return new InventoryDtos.ReceiptReference(reference);
	}
}
