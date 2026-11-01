CREATE TABLE expense_request_ids (
    request_id UUID PRIMARY KEY,
    action VARCHAR(6) NOT NULL CHECK (action IN ('CREATE', 'VOID')),
    request_hash VARCHAR(64) NOT NULL
);

INSERT INTO expense_request_ids (request_id, action, request_hash)
SELECT id, 'CREATE', request_hash FROM expenses;

INSERT INTO expense_request_ids (request_id, action, request_hash)
SELECT e.void_request_id, 'VOID', e.void_hash
FROM expenses e
WHERE e.void_request_id IS NOT NULL
    AND NOT EXISTS (SELECT 1 FROM expenses created WHERE created.id = e.void_request_id);
