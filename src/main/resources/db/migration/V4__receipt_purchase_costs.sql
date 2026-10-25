ALTER TABLE stock_batches ADD COLUMN currency VARCHAR(3);
ALTER TABLE stock_batches ADD COLUMN purchase_unit_cost NUMERIC(19,6);
ALTER TABLE stock_batches ADD COLUMN received_cost NUMERIC(38,6);

ALTER TABLE stock_batches ADD CONSTRAINT ck_stock_batch_cost CHECK (
    (currency IS NULL AND purchase_unit_cost IS NULL AND received_cost IS NULL) OR
    (currency IS NOT NULL AND CHAR_LENGTH(currency) = 3 AND currency = UPPER(currency)
        AND purchase_unit_cost IS NOT NULL AND purchase_unit_cost >= 0
        AND received_cost IS NOT NULL AND received_cost >= 0)
);
