CREATE TABLE produce_types (
    id UUID PRIMARY KEY,
    code VARCHAR(32) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    active BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_produce_type_code CHECK (code = UPPER(code) AND CHAR_LENGTH(TRIM(code)) > 0),
    CONSTRAINT ck_produce_type_name CHECK (CHAR_LENGTH(TRIM(name)) > 0)
);

CREATE TABLE product_classifications (
    id UUID PRIMARY KEY,
    code VARCHAR(32) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    active BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    produce_type_id UUID NOT NULL REFERENCES produce_types(id),
    kind VARCHAR(16) NOT NULL,
    CONSTRAINT ck_classification_kind CHECK (kind IN ('VARIETY', 'GRADE')),
    CONSTRAINT ck_classification_code CHECK (code = UPPER(code) AND CHAR_LENGTH(TRIM(code)) > 0),
    CONSTRAINT ck_classification_name CHECK (CHAR_LENGTH(TRIM(name)) > 0)
);
CREATE INDEX ix_classification_type ON product_classifications(produce_type_id);

CREATE TABLE units_of_measure (
    id UUID PRIMARY KEY,
    code VARCHAR(32) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    active BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    dimension VARCHAR(16) NOT NULL,
    base_factor NUMERIC(19,6),
    CONSTRAINT ck_unit_dimension CHECK (dimension IN ('MASS', 'COUNT', 'PACKAGING')),
    CONSTRAINT ck_unit_factor CHECK (
        (dimension = 'PACKAGING' AND base_factor IS NULL) OR
        (dimension IN ('MASS', 'COUNT') AND base_factor IS NOT NULL AND base_factor > 0)),
    CONSTRAINT ck_unit_code CHECK (code = UPPER(code) AND CHAR_LENGTH(TRIM(code)) > 0),
    CONSTRAINT ck_unit_name CHECK (CHAR_LENGTH(TRIM(name)) > 0)
);

CREATE TABLE products (
    id UUID PRIMARY KEY,
    code VARCHAR(32) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    active BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    produce_type_id UUID NOT NULL REFERENCES produce_types(id),
    variety_id UUID REFERENCES product_classifications(id),
    grade_id UUID REFERENCES product_classifications(id),
    base_unit_id UUID NOT NULL REFERENCES units_of_measure(id),
    CONSTRAINT ck_product_code CHECK (code = UPPER(code) AND CHAR_LENGTH(TRIM(code)) > 0),
    CONSTRAINT ck_product_name CHECK (CHAR_LENGTH(TRIM(name)) > 0)
);
CREATE INDEX ix_product_type ON products(produce_type_id);
CREATE INDEX ix_product_variety ON products(variety_id);
CREATE INDEX ix_product_grade ON products(grade_id);
CREATE INDEX ix_product_unit ON products(base_unit_id);

CREATE TABLE product_packaging (
    id UUID PRIMARY KEY,
    product_id UUID NOT NULL REFERENCES products(id),
    unit_id UUID NOT NULL REFERENCES units_of_measure(id),
    quantity_in_base_unit NUMERIC(19,6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_product_packaging UNIQUE (product_id, unit_id),
    CONSTRAINT ck_packaging_quantity CHECK (quantity_in_base_unit > 0)
);
CREATE INDEX ix_packaging_unit ON product_packaging(unit_id);

INSERT INTO units_of_measure (id, code, name, active, created_at, dimension, base_factor) VALUES
('10000000-0000-0000-0000-000000000001', 'KG', 'Kilogram', TRUE, CURRENT_TIMESTAMP, 'MASS', 1),
('10000000-0000-0000-0000-000000000002', 'G', 'Gram', TRUE, CURRENT_TIMESTAMP, 'MASS', 0.001),
('10000000-0000-0000-0000-000000000003', 'PIECE', 'Piece', TRUE, CURRENT_TIMESTAMP, 'COUNT', 1),
('10000000-0000-0000-0000-000000000004', 'CRATE', 'Crate', TRUE, CURRENT_TIMESTAMP, 'PACKAGING', NULL),
('10000000-0000-0000-0000-000000000005', 'BAG', 'Bag', TRUE, CURRENT_TIMESTAMP, 'PACKAGING', NULL);
