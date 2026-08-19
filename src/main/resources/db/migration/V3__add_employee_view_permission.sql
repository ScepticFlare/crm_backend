-- Adds EMPLOYEE_VIEW: a lighter-weight capability than EMPLOYEE_MANAGE for
-- listing the employee roster, needed so the frontend "Assigned Employee"
-- picker can show real data instead of failing outright for non-admins.
-- ADMIN gets ALL (sees everyone, unchanged from what EMPLOYEE_MANAGE
-- already gave them). MANAGER gets TEAM (sees themselves + direct reports,
-- letting them reassign Leads/Customers within their own team). EMPLOYEE
-- gets no grant at all, same as today - GET /api/employees still 403s for
-- a plain employee.

INSERT INTO permissions (code) VALUES ('EMPLOYEE_VIEW');

INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT r.id, p.id, 'ALL'
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'ADMIN' AND p.code = 'EMPLOYEE_VIEW';

INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT r.id, p.id, 'TEAM'
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'MANAGER' AND p.code = 'EMPLOYEE_VIEW';
