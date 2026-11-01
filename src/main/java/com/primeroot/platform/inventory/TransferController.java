package com.primeroot.platform.inventory;

import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {
	private final TransferService transfers;
	public TransferController(TransferService transfers) { this.transfers = transfers; }
	@GetMapping("/entry")
	public TransferDtos.Entry entry() { return transfers.entry(); }
	@GetMapping
	public TransferDtos.History history(@RequestParam(required = false) StockTransfer.Status status,
			@RequestParam(defaultValue = "0") int page) { return transfers.history(status, page); }
	@GetMapping("/{id}")
	public TransferDtos.Detail detail(@PathVariable UUID id) { return transfers.detail(id); }
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public TransferDtos.Detail dispatch(@Valid @RequestBody TransferDtos.Dispatch request) { return transfers.dispatch(request); }
	@PostMapping("/{id}/receive")
	public TransferDtos.Detail receive(@PathVariable UUID id, @Valid @RequestBody TransferDtos.Receive request) {
		return transfers.receive(id, request);
	}
}
