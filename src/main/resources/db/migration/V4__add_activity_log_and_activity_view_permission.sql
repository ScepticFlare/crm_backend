-- Introduces the Employee Activity / Audit Log feature: a single generic
-- activity_log table recording meaningful business actions (see
-- entity.ActivityLog / enums.ActivityAction / enums.ActivityModule), plus
-- the ACTIVITY_VIEW permission so visibility follows the existing
-- ADMIN=ALL / MANAGER=TEAM / EMPLOYEE=OWN scope model unchanged.

-- 1. The activity log itself. employee_id is nullable with ON DELETE SET
--    NULL (not a required FK) - a required FK would make any employee who
--    ever performed a single tracked action undeletable, breaking
--    EmployeeService.deleteEmployee's existing "admin can delete an
--    employee" flow. employee_name is a snapshot captured at write time so
--    a log entry stays readable after that employee's account is later
--    deleted. entity_id/entity_name are plain columns, not a real FK - the
--    target can be a Lead, Opportunity, Customer, FollowUp, or Employee,
--    and the whole point of an audit trail is to remain readable even
--    after the target record itself is gone.
CREATE TABLE activity_log (
    id            BIGSERIAL PRIMARY KEY,
    employee_id   BIGINT NULL REFERENCES employees(id) ON DELETE SET NULL,
    employee_name VARCHAR(255) NOT NULL,
    action        VARCHAR(30) NOT NULL,
    module        VARCHAR(20) NOT NULL,
    entity_id     BIGINT NULL,
    entity_name   VARCHAR(255) NULL,
    description   VARCHAR(500) NULL,
    created_at    TIMESTAMP NOT NULL
);

-- Query shapes this needs to serve fast: "my/my team's activity, newest
-- first" (employee_id IN (...) ORDER BY created_at DESC - the TEAM/OWN
-- scoped case), "everyone's activity, newest first" (the ALL scoped case),
-- and filtering by module/action.
CREATE INDEX idx_activity_log_employee_created ON activity_log (employee_id, created_at DESC);
CREATE INDEX idx_activity_log_created_at ON activity_log (created_at DESC);
CREATE INDEX idx_activity_log_module_action ON activity_log (module, action);

-- 2. ACTIVITY_VIEW permission, granted the same way EMPLOYEE_VIEW was in
--    V3: ADMIN=ALL (sees everyone's activity), MANAGER=TEAM (sees
--    themselves + direct reports), EMPLOYEE=OWN (sees only their own -
--    plumbing kept consistent with every other permission even though no
--    frontend page is exposed to plain Employees in V1).
INSERT INTO permissions (code) VALUES ('ACTIVITY_VIEW');

INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT r.id, p.id, 'ALL'
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'ADMIN' AND p.code = 'ACTIVITY_VIEW';

INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT r.id, p.id, 'TEAM'
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'MANAGER' AND p.code = 'ACTIVITY_VIEW';

INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT r.id, p.id, 'OWN'
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'EMPLOYEE' AND p.code = 'ACTIVITY_VIEW';
