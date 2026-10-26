-- Representative production schema at Flyway baseline version 1: what Hibernate
-- ddl-auto=update had produced before the first migration (V2). Only the tables
-- and columns the V2..V11 migrations touch, plus a few rows of legacy data.
-- Used by ExportGrantMigrationPostgresTest against a disposable PostgreSQL.

CREATE TABLE employees (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    email      VARCHAR(255) NOT NULL UNIQUE,
    phone      VARCHAR(255) NOT NULL UNIQUE,
    password   VARCHAR(255) NOT NULL,
    role       VARCHAR(255),
    is_active  BOOLEAN,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE industries (id BIGSERIAL PRIMARY KEY, name VARCHAR(255));
CREATE TABLE lead_sources (id BIGSERIAL PRIMARY KEY, name VARCHAR(255));
CREATE TABLE products (id BIGSERIAL PRIMARY KEY, name VARCHAR(255));
CREATE TABLE batteries (id BIGSERIAL PRIMARY KEY, name VARCHAR(255));
CREATE TABLE activity_types (id BIGSERIAL PRIMARY KEY, name VARCHAR(255));

CREATE TABLE sales_stages (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(255) NOT NULL UNIQUE,
    is_active  BOOLEAN,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE leads (
    id                   BIGSERIAL PRIMARY KEY,
    company_name         VARCHAR(255),
    contact_person       VARCHAR(255),
    phone                VARCHAR(255),
    email                VARCHAR(255),
    lead_status          VARCHAR(255),
    lead_validity        VARCHAR(255),
    industry_id          BIGINT REFERENCES industries(id),
    lead_source_id       BIGINT REFERENCES lead_sources(id),
    assigned_employee_id BIGINT REFERENCES employees(id),
    created_at           TIMESTAMP,
    updated_at           TIMESTAMP
);

CREATE TABLE lead_products (id BIGSERIAL PRIMARY KEY, lead_id BIGINT REFERENCES leads(id), product_id BIGINT REFERENCES products(id), quantity INT);
CREATE TABLE lead_batteries (id BIGSERIAL PRIMARY KEY, lead_id BIGINT REFERENCES leads(id), battery_id BIGINT REFERENCES batteries(id), quantity INT);

CREATE TABLE opportunities (
    id                    BIGSERIAL PRIMARY KEY,
    title                 VARCHAR(255),
    product_value         DOUBLE PRECISION,
    expected_closing_date DATE,
    sales_stage_id        BIGINT REFERENCES sales_stages(id),
    lead_validity         VARCHAR(255),
    lead_id               BIGINT REFERENCES leads(id),
    created_at            TIMESTAMP,
    updated_at            TIMESTAMP
);

CREATE TABLE customers (
    id                   BIGSERIAL PRIMARY KEY,
    company_name         VARCHAR(255),
    assigned_employee_id BIGINT REFERENCES employees(id),
    opportunity_id       BIGINT REFERENCES opportunities(id),
    created_at           TIMESTAMP
);

CREATE TABLE followups (
    id               BIGSERIAL PRIMARY KEY,
    lead_id          BIGINT REFERENCES leads(id),
    opportunity_id   BIGINT REFERENCES opportunities(id),
    employee_id      BIGINT REFERENCES employees(id),
    activity_type_id BIGINT REFERENCES activity_types(id),
    status           VARCHAR(255),
    scheduled_date   TIMESTAMP
);

-- legacy data
INSERT INTO employees (name, email, phone, password, role, is_active) VALUES
    ('Asha Admin',   'admin@x.test',   '9000000001', 'x', 'ADMIN',    TRUE),
    ('Mohan Manager','manager@x.test', '9000000002', 'x', 'MANAGER',  TRUE),
    ('Esha Employee','emp@x.test',     '9000000003', 'x', 'EMPLOYEE', TRUE),
    ('Eli Employee', 'emp2@x.test',    '9000000004', 'x', 'EMPLOYEE', TRUE);

INSERT INTO leads (company_name, phone, lead_status, lead_validity, assigned_employee_id, created_at) VALUES
    ('Old Won Co',   '9876543210',          'WON',     'VALID',   3, now()),
    ('Old New Co',   '00441234567890123456','NEW',     'VALID',   3, now()),
    ('Old Invalid',  '+919876543210',       'INVALID', 'INVALID', 4, now());
