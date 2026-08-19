-- Introduces the Admin -> Manager -> Employee role hierarchy and the
-- Role/Permission/Scope authorization model (see security.AccessControlService).
--
-- This is the first Flyway-managed migration in this project (Flyway is
-- baselined at version 1 against whatever schema Hibernate's ddl-auto=update
-- has already produced in each environment - see spring.flyway.* in
-- application.properties). Everything below is additive except the final
-- step, which replaces employees.role (a plain enum-backed string column)
-- with employees.role_id (a foreign key into the new roles table).

-- 1. Role hierarchy tier + permission catalog + the grants tying them
--    together at a visibility scope (OWN / TEAM / ALL).
CREATE TABLE roles (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(50) NOT NULL UNIQUE,
    role_rank  INTEGER NOT NULL
);

CREATE TABLE permissions (
    id   BIGSERIAL PRIMARY KEY,
    code VARCHAR(50) NOT NULL UNIQUE
);

CREATE TABLE role_permissions (
    id            BIGSERIAL PRIMARY KEY,
    role_id       BIGINT NOT NULL REFERENCES roles(id),
    permission_id BIGINT NOT NULL REFERENCES permissions(id),
    scope         VARCHAR(10) NOT NULL,
    CONSTRAINT uq_role_permission UNIQUE (role_id, permission_id)
);

-- 2. Seed the three initial roles.
INSERT INTO roles (name, role_rank) VALUES
    ('ADMIN', 100),
    ('MANAGER', 50),
    ('EMPLOYEE', 10);

-- 3. Seed the permission catalog: two permissions per CRM resource
--    (VIEW covers read/list, MANAGE covers create/update/delete/reassign),
--    plus employee-management, which stays administrator-only.
INSERT INTO permissions (code) VALUES
    ('LEAD_VIEW'), ('LEAD_MANAGE'),
    ('OPPORTUNITY_VIEW'), ('OPPORTUNITY_MANAGE'),
    ('CUSTOMER_VIEW'), ('CUSTOMER_MANAGE'),
    ('FOLLOWUP_VIEW'), ('FOLLOWUP_MANAGE'),
    ('EMPLOYEE_MANAGE');

-- 4. Grant every permission to ADMIN at ALL scope - preserves today's
--    "admin sees/manages everything" behavior exactly.
INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT r.id, p.id, 'ALL'
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'ADMIN';

-- 5. Grant every CRM-record permission to MANAGER at TEAM scope (self plus
--    direct reports - see employees.manager_id below). Employee management
--    stays out of MANAGER's grants.
INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT r.id, p.id, 'TEAM'
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'MANAGER'
  AND p.code <> 'EMPLOYEE_MANAGE';

-- 6. Grant every CRM-record permission to EMPLOYEE at OWN scope - preserves
--    today's "employee sees/manages only their own records" behavior
--    exactly. Employee management stays out of EMPLOYEE's grants too
--    (matching today: only admins could create/list/delete employees).
INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT r.id, p.id, 'OWN'
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'EMPLOYEE'
  AND p.code <> 'EMPLOYEE_MANAGE';

-- 7. Employee hierarchy: self-referential manager relationship, one level
--    deep by design (a Manager's team = themselves + whoever's manager_id
--    points at them). SET NULL on delete so removing a manager orphans
--    their reports rather than blocking the deletion outright.
ALTER TABLE employees
    ADD COLUMN manager_id BIGINT NULL REFERENCES employees(id) ON DELETE SET NULL;

-- 8. Migrate employees.role (VARCHAR, the old enum's storage) to
--    employees.role_id (FK into roles), safely: add nullable, backfill from
--    the existing string values, then enforce NOT NULL and drop the old
--    column - all in one transaction (Flyway runs each migration
--    transactionally on PostgreSQL), so this either fully applies or not at
--    all.
ALTER TABLE employees ADD COLUMN role_id BIGINT NULL REFERENCES roles(id);

UPDATE employees e
SET role_id = r.id
FROM roles r
WHERE r.name = e.role;

-- Safety net: only ADMIN/EMPLOYEE ever existed in the old column, so this
-- should affect zero rows - guards against any unexpected/blank value
-- rather than leaving role_id NULL and failing the NOT NULL step below.
UPDATE employees
SET role_id = (SELECT id FROM roles WHERE name = 'EMPLOYEE')
WHERE role_id IS NULL;

ALTER TABLE employees ALTER COLUMN role_id SET NOT NULL;

ALTER TABLE employees DROP COLUMN role;
