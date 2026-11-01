package com.primeroot.platform.whatsapp;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "platform.whatsapp.enabled", havingValue = "true")
public class WhatsAppController {
	private final WhatsAppLinkService links;
	private final SalesSummaryTool tool;

	public WhatsAppController(WhatsAppLinkService links, SalesSummaryTool tool) {
		this.links = links;
		this.tool = tool;
	}

	public record Confirm(@NotNull UUID challengeId) {}

	@GetMapping("/api/whatsapp/link")
	public WhatsAppLinkService.Status status() { return links.status(); }

	@PostMapping("/api/whatsapp/link/challenges")
	public WhatsAppLinkService.Created create() { return links.create(); }

	@PostMapping("/api/whatsapp/link/confirm")
	public WhatsAppLinkService.Status confirm(@Valid @RequestBody Confirm request,
			@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client) {
		return links.confirm(request.challengeId(), client);
	}

	@DeleteMapping("/api/whatsapp/link")
	public WhatsAppLinkService.Status unlink() { return links.unlink(); }

	@GetMapping("/api/whatsapp/sales-summary")
	public SalesSummaryTool.Summary summary(@RequestParam(required = false) UUID branchId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
		return tool.execute(startDate, endDate, branchId);
	}
}
