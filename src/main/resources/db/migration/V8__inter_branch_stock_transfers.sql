ALTER TABLE stock_operations DROP CONSTRAINT ck_stock_operation_kind;
ALTER TABLE stock_operations ADD CONSTRAINT ck_stock_operation_kind CHECK
    (kind IN ('RECEIPT', 'ADJUSTMENT', 'SPOILAGE', 'REVERSAL', 'SALE', 'SALE_VOID', 'TRANSFER_OUT', 'TRANSFER_IN'));
ALTER TABLE stock_movements DROP CONSTRAINT ck_stock_movement_kind;
ALTER TABLE stock_movements ADD CONSTRAINT ck_stock_movement_kind CHECK
    (kind IN ('RECEIPT', 'ADJUSTMENT', 'SPOILAGE', 'SALE', 'SALE_VOID', 'TRANSFER_OUT', 'TRANSFER_IN'));
ALTER TABLE stock_batches ADD COLUMN source_batch_id UUID REFERENCES stock_batches(id);
ALTER TABLE stock_batches ADD COLUMN product_name_snapshot VARCHAR(120);
ALTER TABLE stock_batches ADD COLUMN product_code_snapshot VARCHAR(32);
ALTER TABLE stock_batches ADD COLUMN produce_type_snapshot VARCHAR(120);
ALTER TABLE stock_batches ADD COLUMN variety_snapshot VARCHAR(120);
ALTER TABLE stock_batches ADD COLUMN grade_snapshot VARCHAR(120);
ALTER TABLE stock_batches ADD COLUMN base_unit_snapshot VARCHAR(32);
UPDATE stock_batches SET
    product_name_snapshot = (SELECT p.name FROM products p WHERE p.id = stock_batches.product_id),
    product_code_snapshot = (SELECT p.code FROM products p WHERE p.id = stock_batches.product_id),
    produce_type_snapshot = (SELECT t.name FROM products p JOIN produce_types t ON t.id = p.produce_type_id WHERE p.id = stock_batches.product_id),
    variety_snapshot = (SELECT c.name FROM products p JOIN product_classifications c ON c.id = p.variety_id WHERE p.id = stock_batches.product_id),
    grade_snapshot = (SELECT c.name FROM products p JOIN product_classifications c ON c.id = p.grade_id WHERE p.id = stock_batches.product_id),
    base_unit_snapshot = (SELECT u.code FROM products p JOIN units_of_measure u ON u.id = p.base_unit_id WHERE p.id = stock_batches.product_id);

CREATE TABLE stock_transfers (
    id UUID PRIMARY KEY REFERENCES stock_operations(id),
    source_id UUID NOT NULL REFERENCES branches(id),
    destination_id UUID NOT NULL REFERENCES branches(id),
    source_name VARCHAR(120) NOT NULL,
    destination_name VARCHAR(120) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('IN_TRANSIT', 'RECEIVED')),
    receive_operation_id UUID UNIQUE REFERENCES stock_operations(id),
    version BIGINT NOT NULL DEFAULT 0,
    CHECK (source_id <> destination_id),
    CHECK ((status = 'IN_TRANSIT' AND receive_operation_id IS NULL)
        OR (status = 'RECEIVED' AND receive_operation_id IS NOT NULL))
);
CREATE INDEX ix_transfer_source ON stock_transfers(source_id);
CREATE INDEX ix_transfer_destination ON stock_transfers(destination_id);

CREATE TABLE transfer_allocations (
    id UUID PRIMARY KEY,
    transfer_id UUID NOT NULL REFERENCES stock_transfers(id),
    movement_id UUID NOT NULL UNIQUE REFERENCES stock_movements(id),
    destination_batch_id UUID UNIQUE REFERENCES stock_batches(id),
    product_name VARCHAR(120) NOT NULL,
    product_code VARCHAR(32) NOT NULL,
    produce_type VARCHAR(120) NOT NULL,
    variety VARCHAR(120),
    grade VARCHAR(120),
    base_unit VARCHAR(32) NOT NULL,
    quantity NUMERIC(19,6) NOT NULL CHECK (quantity > 0),
    cost NUMERIC(38,6) CHECK (cost >= 0),
    received_quantity NUMERIC(19,6),
    shortage_quantity NUMERIC(19,6),
    damaged_quantity NUMERIC(19,6),
    received_cost NUMERIC(38,6) CHECK (received_cost >= 0),
    loss_cost NUMERIC(38,6) CHECK (loss_cost >= 0),
    reason VARCHAR(500),
    CHECK ((received_quantity IS NULL AND shortage_quantity IS NULL AND damaged_quantity IS NULL
        AND destination_batch_id IS NULL AND received_cost IS NULL AND loss_cost IS NULL)
        OR (received_quantity IS NOT NULL AND shortage_quantity IS NOT NULL AND damaged_quantity IS NOT NULL
            AND received_quantity >= 0 AND shortage_quantity >= 0 AND damaged_quantity >= 0
            AND received_quantity + shortage_quantity + damaged_quantity = quantity
            AND ((received_quantity = 0 AND destination_batch_id IS NULL)
                OR (received_quantity > 0 AND destination_batch_id IS NOT NULL)))),
    CHECK (shortage_quantity + damaged_quantity = 0
        OR (reason IS NOT NULL AND CHAR_LENGTH(TRIM(reason)) > 0)),
    CHECK (received_quantity IS NULL
        OR (cost IS NULL AND received_cost IS NULL AND loss_cost IS NULL)
        OR (cost IS NOT NULL AND received_cost IS NOT NULL AND loss_cost IS NOT NULL
            AND received_cost + loss_cost = cost))
);
CREATE INDEX ix_transfer_allocation_transfer ON transfer_allocations(transfer_id);
