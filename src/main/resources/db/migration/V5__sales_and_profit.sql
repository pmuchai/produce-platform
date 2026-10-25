ALTER TABLE stock_operations DROP CONSTRAINT ck_stock_operation_kind;
ALTER TABLE stock_operations ADD CONSTRAINT ck_stock_operation_kind
    CHECK (kind IN ('RECEIPT', 'ADJUSTMENT', 'SPOILAGE', 'REVERSAL', 'SALE', 'SALE_VOID'));
ALTER TABLE stock_movements DROP CONSTRAINT ck_stock_movement_kind;
ALTER TABLE stock_movements ADD CONSTRAINT ck_stock_movement_kind
    CHECK (kind IN ('RECEIPT', 'ADJUSTMENT', 'SPOILAGE', 'SALE', 'SALE_VOID'));
ALTER TABLE stock_batches ADD COLUMN remaining_cost NUMERIC(38,6);
UPDATE stock_batches SET remaining_cost = ROUND(received_cost * quantity / received_quantity,
    CASE
        WHEN currency IN ('ADP', 'BEF', 'BIF', 'BYB', 'BYR', 'CLP', 'DJF', 'ESP', 'GNF', 'GRD',
            'ISK', 'ITL', 'JPY', 'KMF', 'KRW', 'LUF', 'MGF', 'PTE', 'PYG', 'ROL', 'RWF',
            'TPE', 'TRL', 'UGX', 'UYI', 'VND', 'VUV', 'XAF', 'XOF', 'XPF') THEN 0
        WHEN currency IN ('BHD', 'IQD', 'JOD', 'KWD', 'LYD', 'OMR', 'TND') THEN 3
        WHEN currency = 'CLF' THEN 4
        ELSE 2
    END)
    WHERE received_cost IS NOT NULL;
ALTER TABLE stock_batches ADD CONSTRAINT ck_stock_remaining_cost CHECK (remaining_cost >= 0);
ALTER TABLE stock_movements ADD COLUMN cost_change NUMERIC(38,6);

CREATE TABLE sales (
    id UUID PRIMARY KEY REFERENCES stock_operations(id),
    branch_id UUID NOT NULL REFERENCES branches(id),
    seller_id UUID NOT NULL REFERENCES staff_users(id),
    seller_name VARCHAR(120) NOT NULL,
    branch_name VARCHAR(120) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    customer VARCHAR(120),
    payment_method VARCHAR(16) NOT NULL CHECK (payment_method IN ('CASH', 'MPESA', 'BANK')),
    payment_reference VARCHAR(120),
    total NUMERIC(38,6) NOT NULL CHECK (total > 0),
    cost NUMERIC(38,6) NOT NULL CHECK (cost >= 0),
    void_operation_id UUID UNIQUE REFERENCES stock_operations(id),
    refund_reference VARCHAR(120),
    CONSTRAINT uq_sale_payment_reference UNIQUE (payment_method, payment_reference),
    CONSTRAINT ck_sale_payment_reference CHECK
        (payment_method = 'CASH' OR (payment_reference IS NOT NULL AND CHAR_LENGTH(TRIM(payment_reference)) > 0))
);
CREATE INDEX ix_sales_branch_seller ON sales(branch_id, seller_id);

CREATE TABLE sale_lines (
    id UUID PRIMARY KEY,
    sale_id UUID NOT NULL REFERENCES sales(id),
    line_number INTEGER NOT NULL CHECK (line_number > 0),
    product_id UUID NOT NULL REFERENCES products(id),
    product_name VARCHAR(120) NOT NULL,
    product_code VARCHAR(32) NOT NULL,
    input_unit VARCHAR(32) NOT NULL,
    base_unit VARCHAR(32) NOT NULL,
    quantity NUMERIC(19,6) NOT NULL CHECK (quantity > 0),
    base_quantity NUMERIC(19,6) NOT NULL CHECK (base_quantity > 0),
    conversion_numerator NUMERIC(19,6),
    conversion_denominator NUMERIC(19,6),
    measured BOOLEAN NOT NULL,
    unit_price NUMERIC(19,6) NOT NULL CHECK (unit_price > 0),
    total NUMERIC(38,6) NOT NULL CHECK (total > 0),
    cost NUMERIC(38,6) NOT NULL CHECK (cost >= 0),
    CONSTRAINT uq_sale_line_number UNIQUE (sale_id, line_number)
);
CREATE INDEX ix_sale_line_product ON sale_lines(product_id);

CREATE TABLE sale_allocations (
    id UUID PRIMARY KEY,
    line_id UUID NOT NULL REFERENCES sale_lines(id),
    movement_id UUID NOT NULL UNIQUE REFERENCES stock_movements(id),
    quantity NUMERIC(19,6) NOT NULL CHECK (quantity > 0),
    cost NUMERIC(38,6) NOT NULL CHECK (cost >= 0)
);
CREATE INDEX ix_sale_allocation_line ON sale_allocations(line_id);

CREATE VIEW sales_events AS
    SELECT s.id AS sale_id, s.branch_id, s.seller_id, s.currency, o.posted_at AS occurred_at,
           1 AS direction, s.total AS revenue, s.cost, s.payment_method
    FROM sales s JOIN stock_operations o ON o.id = s.id
    UNION ALL
    SELECT s.id AS sale_id, s.branch_id, s.seller_id, s.currency, o.posted_at AS occurred_at,
           -1 AS direction, -s.total AS revenue, -s.cost, s.payment_method
    FROM sales s JOIN stock_operations o ON o.id = s.void_operation_id;
