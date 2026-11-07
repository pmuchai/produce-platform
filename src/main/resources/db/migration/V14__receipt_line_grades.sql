ALTER TABLE stock_batches ADD COLUMN grade_id UUID REFERENCES product_classifications(id);
CREATE INDEX ix_stock_batch_grade ON stock_batches(grade_id);

UPDATE stock_batches SET grade_id = (
    SELECT c.id FROM products p JOIN product_classifications c ON c.id = p.grade_id
    WHERE p.id = stock_batches.product_id AND c.kind = 'GRADE'
      AND c.name = stock_batches.grade_snapshot
);

ALTER TABLE products DROP COLUMN grade_id;
