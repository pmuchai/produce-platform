CREATE TABLE branches (
    id UUID PRIMARY KEY,
    code VARCHAR(32) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    address VARCHAR(255),
    active BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_branch_code CHECK (code = UPPER(code) AND CHAR_LENGTH(TRIM(code)) > 0),
    CONSTRAINT ck_branch_name CHECK (CHAR_LENGTH(TRIM(name)) > 0)
);

CREATE TABLE staff_users (
    id UUID PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    full_name VARCHAR(120) NOT NULL,
    issuer VARCHAR(1024) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    active BOOLEAN NOT NULL,
    all_branches BOOLEAN NOT NULL DEFAULT FALSE,
    security_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_staff_identity UNIQUE (issuer, subject),
    CONSTRAINT ck_staff_username CHECK (username = LOWER(username) AND CHAR_LENGTH(TRIM(username)) >= 3),
    CONSTRAINT ck_staff_name CHECK (CHAR_LENGTH(TRIM(full_name)) > 0),
    CONSTRAINT ck_staff_subject CHECK (CHAR_LENGTH(TRIM(subject)) > 0),
    CONSTRAINT ck_staff_issuer CHECK (CHAR_LENGTH(TRIM(issuer)) > 0)
);

CREATE TABLE user_branches (
    user_id UUID NOT NULL REFERENCES staff_users(id),
    branch_id UUID NOT NULL REFERENCES branches(id),
    PRIMARY KEY (user_id, branch_id)
);
CREATE INDEX ix_user_branches_branch ON user_branches(branch_id);

CREATE TABLE audit_events (
    id UUID PRIMARY KEY,
    actor_id UUID REFERENCES staff_users(id),
    action VARCHAR(64) NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_id UUID NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_audit_actor ON audit_events(actor_id);
CREATE INDEX ix_audit_target ON audit_events(target_type, target_id);
CREATE INDEX ix_audit_occurred_at ON audit_events(occurred_at);
