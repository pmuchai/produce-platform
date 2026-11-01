ALTER TABLE sales ADD COLUMN sale_date DATE;
UPDATE sales SET sale_date = (
    SELECT CAST(SUBSTRING(CAST(o.posted_at AT TIME ZONE 'UTC' AS VARCHAR), 1, 10) AS DATE)
    FROM stock_operations o WHERE o.id = sales.id
);
ALTER TABLE sales ALTER COLUMN sale_date SET NOT NULL;
ALTER TABLE sales ADD CONSTRAINT ck_sale_date CHECK (sale_date >= DATE '1970-01-01');
CREATE INDEX ix_sales_branch_date ON sales(branch_id, sale_date);

DROP VIEW sales_events;
CREATE VIEW sales_events AS
    SELECT s.id AS sale_id, s.branch_id, s.seller_id, s.currency,
           CASE WHEN s.sale_date = CAST(SUBSTRING(CAST(o.posted_at AT TIME ZONE 'UTC' AS VARCHAR), 1, 10) AS DATE)
               THEN o.posted_at
               ELSE CAST(CONCAT(CAST(s.sale_date AS VARCHAR), ' 00:00:00+00:00') AS TIMESTAMP WITH TIME ZONE)
           END AS occurred_at,
           1 AS direction, s.total AS revenue, s.cost, s.payment_method
    FROM sales s JOIN stock_operations o ON o.id = s.id
    UNION ALL
    SELECT s.id AS sale_id, s.branch_id, s.seller_id, s.currency, o.posted_at AS occurred_at,
           -1 AS direction, -s.total AS revenue, -s.cost, s.payment_method
    FROM sales s JOIN stock_operations o ON o.id = s.void_operation_id;
