-- Marketing role: lets an Admin give selected employees lead/opportunity
-- export WITHOUT granting export to every employee.
--
-- Purely additive and idempotent. No existing employee is assigned this
-- role and no existing role's permissions change - an Admin must assign it
-- explicitly (Employees -> Edit -> Role = MARKETING).
--
-- Grants: exactly what EMPLOYEE has (copied, so the two stay aligned at
-- migration time), all at OWN scope, plus LEAD_EXPORT and OPPORTUNITY_EXPORT
-- at OWN scope. Export is always limited to the narrower of the export and
-- view scope (AccessControlService.resolveExportEmployeeIds), so a marketing
-- user exports only the leads/opportunities they own. To let marketing export
-- more, an Admin raises that role's LEAD_VIEW/OPPORTUNITY_VIEW (and export)
-- scope in role_permissions - nothing is widened here.
-- No Customer / Follow-up export, and no delete.

INSERT INTO roles (name, role_rank)
SELECT 'MARKETING', 10
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE name = 'MARKETING');

INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT m.id, rp.permission_id, rp.scope
FROM roles m
JOIN roles e ON e.name = 'EMPLOYEE'
JOIN role_permissions rp ON rp.role_id = e.id
WHERE m.name = 'MARKETING'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions x
      WHERE x.role_id = m.id AND x.permission_id = rp.permission_id
  );

INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT m.id, p.id, 'OWN'
FROM roles m
JOIN permissions p ON p.code IN ('LEAD_EXPORT', 'OPPORTUNITY_EXPORT')
WHERE m.name = 'MARKETING'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions x
      WHERE x.role_id = m.id AND x.permission_id = p.id
  );
