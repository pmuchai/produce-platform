CREATE TABLE receipt_reference_reservations (
    id UUID PRIMARY KEY,
    branch_id UUID NOT NULL REFERENCES branches(id) ON DELETE CASCADE,
    actor_id UUID NOT NULL REFERENCES staff_users(id) ON DELETE CASCADE,
    received_date DATE NOT NULL,
    sequence_number BIGINT NOT NULL CHECK (sequence_number > 0),
    reference VARCHAR(120) NOT NULL UNIQUE,
    UNIQUE (branch_id, received_date, sequence_number)
);
