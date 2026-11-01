package com.primeroot.platform.whatsapp;

import java.time.DayOfWeek;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;

import com.primeroot.platform.security.StaffPrincipal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "platform.whatsapp.enabled", havingValue = "true")
public class WhatsAppWorker {
	private final WhatsAppProperties properties;
	private final WhatsAppStore store;
	private final WhatsAppAuthorization authorization;
	private final WhatsAppLinkService links;
	private final SalesSummaryTool tool;
	private final WhatsAppTransport transport;

	public WhatsAppWorker(WhatsAppProperties properties, WhatsAppStore store, WhatsAppAuthorization authorization,
			WhatsAppLinkService links, SalesSummaryTool tool, WhatsAppTransport transport) {
		this.properties = properties;
		this.store = store;
		this.authorization = authorization;
		this.links = links;
		this.tool = tool;
		this.transport = transport;
	}

	@Scheduled(fixedDelayString = "${platform.whatsapp.poll-delay:1000}")
	public void poll() {
		if (properties.workerEnabled()) processOne();
	}

	@Scheduled(fixedDelay = 86_400_000)
	public void purge() {
		if (properties.workerEnabled()) store.purge();
	}

	public boolean processOne() {
		var next = store.claim();
		if (next.isEmpty()) return false;
		var message = next.get();
		var previous = SecurityContextHolder.getContext();
		StaffPrincipal principal = null;
		try {
			String reply;
			if ("LINK".equals(message.command())) {
				reply = links.verify(message.challengeHash(), message.sender());
			}
			else if ("HELP".equals(message.command())) {
				reply = "Sign in to Prime Root and open WhatsApp to link your account. Supported prompts: "
					+ "\"What is the total amount sold today?\", \"sales yesterday\", \"sales this week\". Dates use UTC.";
			}
			else {
				try {
					principal = authorization.authenticate(message.sender(), message.linkId());
					var context = SecurityContextHolder.createEmptyContext();
					context.setAuthentication(new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "keycloak"));
					SecurityContextHolder.setContext(context);
					var end = message.sentAt().atZone(ZoneOffset.UTC).toLocalDate();
					var start = end;
					if ("YESTERDAY".equals(message.command())) start = end = end.minusDays(1);
					if ("WEEK".equals(message.command())) start = end.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
					reply = SalesSummaryTool.reply(tool.execute(start, end, null));
					if (!authorization.stillLinked(message.sender(), message.linkId(), principal)) throw WhatsAppOidcClient.denied();
				}
				catch (AccessDeniedException exception) {
					reply = "Sales reporting access is unavailable. Sign in to Prime Root and renew WhatsApp linking, "
						+ "or ask your administrator to check your staff access.";
					principal = null;
				}
			}
			if (principal != null && !authorization.stillLinked(message.sender(), message.linkId(), principal)) {
				reply = "WhatsApp access changed. Sign in to Prime Root and renew linking.";
			}
			if (store.sending(message)) {
				transport.send(message.sender(), reply);
				store.finish(message, "SENT");
			}
		}
		catch (WhatsAppOidcClient.TemporarilyUnavailable exception) {
			store.finish(message, message.attempts() < 3 ? "PENDING" : "FAILED");
		}
		catch (WhatsAppTransport.DeliveryFailure exception) {
			store.finish(message, exception.uncertain ? "UNCERTAIN"
				: exception.retryable && message.attempts() < 3 ? "PENDING" : "FAILED");
		}
		catch (RuntimeException exception) {
			store.finish(message, "FAILED");
			org.slf4j.LoggerFactory.getLogger(WhatsAppWorker.class).warn("WhatsApp request failed; inspect queue status and configuration");
		}
		finally {
			SecurityContextHolder.setContext(previous);
		}
		return true;
	}
}
