CREATE TABLE whatsapp_challenges (
    staff_id UUID PRIMARY KEY REFERENCES staff_users(id) ON DELETE CASCADE,
    id UUID NOT NULL UNIQUE,
    code_hash VARCHAR(64) NOT NULL UNIQUE,
    security_version BIGINT NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    verified_sender VARCHAR(15)
);

CREATE TABLE whatsapp_links (
    staff_id UUID PRIMARY KEY REFERENCES staff_users(id) ON DELETE CASCADE,
    id UUID NOT NULL UNIQUE,
    sender VARCHAR(15) NOT NULL UNIQUE,
    security_version BIGINT NOT NULL,
    refresh_token VARCHAR(16384) NOT NULL,
    nonce_hash VARCHAR(64),
    linked_at TIMESTAMP WITH TIME ZONE NOT NULL,
    reauthentication_required BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE whatsapp_queue_lock (id INTEGER PRIMARY KEY);
INSERT INTO whatsapp_queue_lock (id) VALUES (1);

CREATE TABLE whatsapp_messages (
    id VARCHAR(255) PRIMARY KEY,
    sender VARCHAR(15) NOT NULL,
    command VARCHAR(32) NOT NULL,
    challenge_hash VARCHAR(64),
    link_id UUID,
    sent_at TIMESTAMP WITH TIME ZONE NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMP WITH TIME ZONE NOT NULL,
    claim_id UUID,
    CONSTRAINT ck_whatsapp_status CHECK (status IN
        ('PENDING', 'PROCESSING', 'SENDING', 'SENT', 'FAILED', 'UNCERTAIN', 'DROPPED'))
);
CREATE INDEX ix_whatsapp_pending ON whatsapp_messages(status, available_at);
CREATE INDEX ix_whatsapp_sender_received ON whatsapp_messages(sender, received_at);
