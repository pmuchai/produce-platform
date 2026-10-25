CREATE TABLE staff_access_requests (
    id UUID PRIMARY KEY,
    issuer VARCHAR(1024) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    suggested_username VARCHAR(64) NOT NULL,
    full_name VARCHAR(120) NOT NULL,
    approved BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_access_request_identity UNIQUE (issuer, subject)
);
CREATE INDEX ix_access_request_pending ON staff_access_requests(issuer, approved, created_at);
