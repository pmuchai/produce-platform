package com.primeroot.platform.whatsapp;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@ConditionalOnProperty(name = "platform.whatsapp.enabled", havingValue = "true")
public class WhatsAppStore {
	private final JdbcTemplate jdbc;

	public WhatsAppStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public record Message(String id, String sender, String command, String challengeHash,
			Instant sentAt, int attempts, UUID claimId, UUID linkId) {}
	public record Link(UUID staffId, UUID id, String sender, long securityVersion, String refreshToken,
			String nonceHash, boolean reauthenticationRequired) {}
	public record Challenge(UUID id, UUID staffId, long securityVersion, Instant expiresAt, String verifiedSender) {}

	void lockQueue() {
		jdbc.queryForObject("SELECT id FROM whatsapp_queue_lock WHERE id = 1 FOR UPDATE", Integer.class);
	}

	void lockStaff(UUID id) {
		jdbc.queryForObject("SELECT id FROM staff_users WHERE id = ? FOR UPDATE", UUID.class, id);
	}

	Optional<Link> link(UUID staffId, boolean lock) {
		return jdbc.query("SELECT * FROM whatsapp_links WHERE staff_id = ?" + (lock ? " FOR UPDATE" : ""),
			(rs, index) -> new Link(rs.getObject("staff_id", UUID.class), rs.getObject("id", UUID.class), rs.getString("sender"),
				rs.getLong("security_version"), rs.getString("refresh_token"), rs.getString("nonce_hash"),
				rs.getBoolean("reauthentication_required")), staffId).stream().findFirst();
	}

	Optional<Link> sender(String sender) {
		return jdbc.query("SELECT staff_id FROM whatsapp_links WHERE sender = ?",
			(rs, index) -> rs.getObject(1, UUID.class), sender).stream().findFirst().flatMap(id -> link(id, false));
	}

	Optional<Challenge> challenge(UUID staffId) {
		return jdbc.query("SELECT * FROM whatsapp_challenges WHERE staff_id = ?", (rs, index) ->
			new Challenge(rs.getObject("id", UUID.class), rs.getObject("staff_id", UUID.class),
				rs.getLong("security_version"), rs.getTimestamp("expires_at").toInstant(),
				rs.getString("verified_sender")), staffId).stream().findFirst();
	}

	Optional<Challenge> challengeByHash(String hash) {
		return jdbc.query("SELECT staff_id FROM whatsapp_challenges WHERE code_hash = ?",
			(rs, index) -> rs.getObject(1, UUID.class), hash).stream().findFirst().flatMap(this::challenge);
	}

	void newChallenge(UUID staffId, UUID id, String hash, long version, Instant expires) {
		jdbc.update("DELETE FROM whatsapp_challenges WHERE staff_id = ?", staffId);
		jdbc.update("""
			INSERT INTO whatsapp_challenges (staff_id, id, code_hash, security_version, expires_at)
			VALUES (?, ?, ?, ?, ?)
			""", staffId, id, hash, version, Timestamp.from(expires));
	}

	boolean verify(UUID id, String sender) {
		return jdbc.update("UPDATE whatsapp_challenges SET verified_sender = ? WHERE id = ? AND verified_sender IS NULL",
			sender, id) == 1;
	}

	void saveLink(Link link) {
		jdbc.update("DELETE FROM whatsapp_links WHERE staff_id = ?", link.staffId());
		jdbc.update("""
			INSERT INTO whatsapp_links (staff_id, id, sender, security_version, refresh_token, nonce_hash, linked_at)
			VALUES (?, ?, ?, ?, ?, ?, ?)
			""", link.staffId(), link.id(), link.sender(), link.securityVersion(), link.refreshToken(), link.nonceHash(),
			Timestamp.from(Instant.now()));
		jdbc.update("DELETE FROM whatsapp_challenges WHERE staff_id = ?", link.staffId());
	}

	void deleteLink(UUID staffId) {
		jdbc.update("DELETE FROM whatsapp_links WHERE staff_id = ?", staffId);
		jdbc.update("DELETE FROM whatsapp_challenges WHERE staff_id = ?", staffId);
	}

	void updateGrant(UUID staffId, String encrypted) {
		jdbc.update("UPDATE whatsapp_links SET refresh_token = ?, reauthentication_required = FALSE WHERE staff_id = ?",
			encrypted, staffId);
	}

	void requireReauthentication(UUID staffId) {
		jdbc.update("UPDATE whatsapp_links SET reauthentication_required = TRUE WHERE staff_id = ?", staffId);
	}

	@Transactional
	public void enqueue(List<Message> messages) {
		lockQueue();
		var now = Instant.now();
		for (var message : messages) {
			if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT COUNT(*) > 0 FROM whatsapp_messages WHERE id = ?",
					Boolean.class, message.id()))) continue;
			var count = jdbc.queryForObject("""
				SELECT COUNT(*) FROM whatsapp_messages WHERE sender = ? AND received_at > ?
				""", Long.class, message.sender(), Timestamp.from(now.minusSeconds(60)));
			jdbc.update("""
				INSERT INTO whatsapp_messages (id, sender, command, challenge_hash, link_id, sent_at, received_at, status, available_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", message.id(), message.sender(), message.command(), message.challengeHash(),
				sender(message.sender()).map(Link::id).orElse(null), Timestamp.from(message.sentAt()),
				Timestamp.from(now), count >= 10 ? "DROPPED" : "PENDING", Timestamp.from(now));
		}
	}

	@Transactional
	public Optional<Message> claim() {
		lockQueue();
		var now = Timestamp.from(Instant.now());
		jdbc.update("UPDATE whatsapp_messages SET status = 'UNCERTAIN' WHERE status = 'SENDING' AND available_at <= ?", now);
		jdbc.update("""
			UPDATE whatsapp_messages SET status = 'FAILED'
			WHERE status IN ('PENDING', 'PROCESSING') AND (sent_at <= ? OR attempts >= 3) AND available_at <= ?
			""", Timestamp.from(Instant.now().minusSeconds(86_400)), now);
		var rows = jdbc.query("""
			SELECT * FROM whatsapp_messages WHERE status IN ('PENDING', 'PROCESSING') AND available_at <= ?
			ORDER BY received_at, id LIMIT 1
			""", (rs, index) -> new Message(rs.getString("id"), rs.getString("sender"), rs.getString("command"),
				rs.getString("challenge_hash"), rs.getTimestamp("sent_at").toInstant(), rs.getInt("attempts") + 1,
				UUID.randomUUID(), rs.getObject("link_id", UUID.class)), now);
		if (rows.isEmpty()) return Optional.empty();
		var message = rows.getFirst();
		jdbc.update("""
			UPDATE whatsapp_messages SET status = 'PROCESSING', attempts = ?, claim_id = ?, available_at = ? WHERE id = ?
			""", message.attempts(), message.claimId(), Timestamp.from(Instant.now().plusSeconds(120)), message.id());
		return Optional.of(message);
	}

	@Transactional
	public boolean sending(Message message) {
		return jdbc.update("""
			UPDATE whatsapp_messages SET status = 'SENDING', available_at = ?
			WHERE id = ? AND claim_id = ? AND status = 'PROCESSING' AND available_at > ? AND sent_at > ?
			""", Timestamp.from(Instant.now().plusSeconds(120)), message.id(), message.claimId(),
			Timestamp.from(Instant.now()), Timestamp.from(Instant.now().minusSeconds(86_400))) == 1;
	}

	@Transactional
	public void finish(Message message, String status) {
		jdbc.update("""
			UPDATE whatsapp_messages SET status = ?, available_at = ? WHERE id = ? AND claim_id = ?
			""", status, Timestamp.from(Instant.now().plusSeconds(30L * message.attempts())), message.id(), message.claimId());
	}

	@Transactional
	public void purge() {
		jdbc.update("DELETE FROM whatsapp_messages WHERE received_at < ?", Timestamp.from(Instant.now().minusSeconds(30L * 86_400)));
		jdbc.update("DELETE FROM whatsapp_challenges WHERE expires_at < ?", Timestamp.from(Instant.now()));
	}
}
