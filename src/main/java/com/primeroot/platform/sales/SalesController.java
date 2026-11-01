package com.primeroot.platform.sales;

import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sales")
public class SalesController {
	private final SalesService sales;
	public SalesController(SalesService sales) { this.sales = sales; }

	@GetMapping("/entry")
	public SalesDtos.Entry entry() { return sales.entry(); }

	@PostMapping("/quote")
	public SalesDtos.Quote quote(@Valid @RequestBody SalesDtos.QuoteRequest request) {
		return sales.quote(request);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public SalesDtos.Receipt create(@Valid @RequestBody SalesDtos.CreateRequest request) {
		return sales.create(request);
	}

	@GetMapping("/{id}")
	public SalesDtos.Receipt receipt(@PathVariable UUID id) { return sales.receipt(id); }

	@PostMapping("/{id}/void")
	public SalesDtos.Receipt voidSale(@PathVariable UUID id, @Valid @RequestBody SalesDtos.VoidRequest request) {
		return sales.voidSale(id, request);
	}
}
