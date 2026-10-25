package com.primeroot.platform.inventory;

import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;

import com.primeroot.platform.inventory.InventoryDtos.ChangeRequest;
import com.primeroot.platform.inventory.InventoryDtos.BatchPage;
import com.primeroot.platform.inventory.InventoryDtos.OperationResponse;
import com.primeroot.platform.inventory.InventoryDtos.ReceiptRequest;
import com.primeroot.platform.inventory.InventoryDtos.ReverseRequest;
import com.primeroot.platform.inventory.InventoryDtos.Snapshot;
import com.primeroot.platform.inventory.InventoryDtos.ThresholdRequest;
import com.primeroot.platform.inventory.InventoryDtos.ThresholdResponse;
import com.primeroot.platform.ui.DashboardPeriod;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {
	private final InventoryService inventory;

	public InventoryController(InventoryService inventory) { this.inventory = inventory; }

	@GetMapping
	public Snapshot list(@RequestParam(required = false) UUID branchId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
			@RequestParam(defaultValue = "0") int page) {
		return inventory.snapshot(branchId, DashboardPeriod.resolve(start, end), page);
	}

	@GetMapping("/batches")
	public BatchPage batches(@RequestParam(required = false) UUID branchId,
			@RequestParam(required = false) String search, @RequestParam(defaultValue = "0") int page) {
		return inventory.batchPage(branchId, search, page);
	}

	@PostMapping("/receipts")
	public ResponseEntity<OperationResponse> receive(@Valid @RequestBody ReceiptRequest request) {
		return ResponseEntity.created(URI.create("/api/inventory")).body(inventory.receive(request));
	}

	@PostMapping("/spoilage")
	public OperationResponse spoil(@Valid @RequestBody ChangeRequest request) { return inventory.spoil(request); }

	@PostMapping("/adjustments")
	public OperationResponse adjust(@Valid @RequestBody ChangeRequest request) { return inventory.adjust(request); }

	@PostMapping("/reversals")
	public OperationResponse reverse(@Valid @RequestBody ReverseRequest request) { return inventory.reverse(request); }

	@PutMapping("/thresholds")
	public ThresholdResponse threshold(@Valid @RequestBody ThresholdRequest request) { return inventory.threshold(request); }
}
