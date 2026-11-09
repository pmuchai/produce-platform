ALTER TABLE sales ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE sale_date_corrections (
    id UUID PRIMARY KEY,
    sale_id UUID NOT NULL REFERENCES sales(id),
    actor_id UUID NOT NULL REFERENCES staff_users(id),
    actor_name VARCHAR(120) NOT NULL,
    previous_date DATE NOT NULL,
    corrected_date DATE NOT NULL,
    reason VARCHAR(500) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    corrected_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_sale_date_correction CHECK (
        corrected_date >= DATE '1970-01-01' AND previous_date <> corrected_date
    )
);
CREATE INDEX ix_sale_date_corrections_sale ON sale_date_corrections(sale_id, corrected_at, id);
