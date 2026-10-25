CREATE TABLE stock_operations (
    id UUID PRIMARY KEY,
    branch_id UUID NOT NULL REFERENCES branches(id),
    actor_id UUID NOT NULL REFERENCES staff_users(id),
    kind VARCHAR(16) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    source VARCHAR(120),
    notes VARCHAR(500),
    posted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_stock_operation_kind CHECK (kind IN ('RECEIPT', 'ADJUSTMENT', 'SPOILAGE', 'REVERSAL'))
);
CREATE INDEX ix_stock_operation_branch_date ON stock_operations(branch_id, posted_at);

CREATE TABLE stock_batches (
    id UUID PRIMARY KEY,
    operation_id UUID NOT NULL REFERENCES stock_operations(id),
    branch_id UUID NOT NULL REFERENCES branches(id),
    product_id UUID NOT NULL REFERENCES products(id),
    lot_code VARCHAR(64) NOT NULL,
    received_date DATE NOT NULL,
    expiry_date DATE,
    input_unit_id UUID NOT NULL REFERENCES units_of_measure(id),
    input_quantity NUMERIC(19,6) NOT NULL,
    received_quantity NUMERIC(19,6) NOT NULL,
    conversion_numerator NUMERIC(19,6),
    conversion_denominator NUMERIC(19,6),
    measured BOOLEAN NOT NULL,
    quantity NUMERIC(19,6) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_stock_batch_quantity CHECK (quantity >= 0 AND received_quantity > 0 AND input_quantity > 0),
    CONSTRAINT ck_stock_batch_lot CHECK (CHAR_LENGTH(TRIM(lot_code)) > 0),
    CONSTRAINT ck_stock_batch_expiry CHECK (expiry_date IS NULL OR expiry_date >= received_date),
    CONSTRAINT ck_stock_batch_conversion CHECK (
        (measured = TRUE AND conversion_numerator IS NULL AND conversion_denominator IS NULL) OR
        (measured = FALSE AND conversion_numerator IS NOT NULL AND conversion_numerator > 0
            AND conversion_denominator IS NOT NULL AND conversion_denominator > 0))
);
CREATE INDEX ix_stock_batch_branch_product ON stock_batches(branch_id, product_id);
CREATE INDEX ix_stock_batch_expiry ON stock_batches(expiry_date);

CREATE TABLE stock_movements (
    id UUID PRIMARY KEY,
    operation_id UUID NOT NULL REFERENCES stock_operations(id),
    batch_id UUID NOT NULL REFERENCES stock_batches(id),
    kind VARCHAR(16) NOT NULL,
    quantity NUMERIC(19,6) NOT NULL,
    reversal_of UUID UNIQUE REFERENCES stock_movements(id),
    CONSTRAINT ck_stock_movement_kind CHECK (kind IN ('RECEIPT', 'ADJUSTMENT', 'SPOILAGE')),
    CONSTRAINT ck_stock_movement_quantity CHECK (quantity <> 0)
);
CREATE INDEX ix_stock_movement_operation ON stock_movements(operation_id);
CREATE INDEX ix_stock_movement_batch ON stock_movements(batch_id);

CREATE TABLE stock_thresholds (
    id UUID PRIMARY KEY,
    branch_id UUID NOT NULL REFERENCES branches(id),
    product_id UUID NOT NULL REFERENCES products(id),
    minimum_quantity NUMERIC(19,6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_stock_threshold UNIQUE (branch_id, product_id),
    CONSTRAINT ck_stock_threshold_quantity CHECK (minimum_quantity >= 0)
);
