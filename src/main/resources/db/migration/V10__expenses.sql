CREATE TABLE expenses (
    id UUID PRIMARY KEY,
    branch_id UUID NOT NULL REFERENCES branches(id),
    branch_name VARCHAR(120) NOT NULL,
    actor_id UUID NOT NULL REFERENCES staff_users(id),
    actor_name VARCHAR(120) NOT NULL,
    posted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    paid_date DATE NOT NULL CHECK (paid_date >= DATE '1970-01-01'),
    category VARCHAR(24) NOT NULL CHECK (category IN ('RENT', 'UTILITIES', 'TRANSPORT', 'WAGES', 'MAINTENANCE', 'OTHER')),
    description VARCHAR(500) NOT NULL CHECK (CHAR_LENGTH(TRIM(description)) > 0),
    payee VARCHAR(120),
    currency VARCHAR(3) NOT NULL,
    amount NUMERIC(38,6) NOT NULL CHECK (amount > 0),
    payment_method VARCHAR(16) NOT NULL CHECK (payment_method IN ('CASH', 'MPESA', 'BANK')),
    payment_reference VARCHAR(120),
    request_hash VARCHAR(64) NOT NULL,
    void_request_id UUID UNIQUE,
    void_hash VARCHAR(64),
    voided_at TIMESTAMP WITH TIME ZONE,
    voided_by UUID REFERENCES staff_users(id),
    void_reason VARCHAR(500),
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (payment_method, payment_reference),
    CHECK (payment_method = 'CASH' OR (payment_reference IS NOT NULL AND CHAR_LENGTH(TRIM(payment_reference)) > 0)),
    CHECK ((void_request_id IS NULL AND void_hash IS NULL AND voided_at IS NULL AND voided_by IS NULL AND void_reason IS NULL)
        OR (void_request_id IS NOT NULL AND void_hash IS NOT NULL AND voided_at IS NOT NULL AND voided_by IS NOT NULL
            AND void_reason IS NOT NULL AND CHAR_LENGTH(TRIM(void_reason)) > 0))
);
CREATE INDEX ix_expense_branch_paid ON expenses(branch_id, paid_date);

CREATE VIEW expense_events AS
SELECT id AS expense_id, branch_id, currency, category,
    CAST(CONCAT(CAST(paid_date AS VARCHAR), ' 00:00:00+00:00') AS TIMESTAMP WITH TIME ZONE) AS occurred_at, amount
FROM expenses
UNION ALL
SELECT id AS expense_id, branch_id, currency, category, voided_at AS occurred_at, -amount
FROM expenses WHERE voided_at IS NOT NULL;
