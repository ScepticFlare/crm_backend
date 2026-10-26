-- Lead and opportunity export for every existing role.
--
-- V5 made LEAD_EXPORT / OPPORTUNITY_EXPORT admin-only. The business rule is now
-- that every employee may export leads and opportunities with their own login,
-- WITHOUT any role change. This is data only: it grants the two export
-- permissions to each role that already has the matching *_VIEW permission, at
-- exactly that role's *_VIEW scope (ADMIN ALL, MANAGER TEAM, EMPLOYEE OWN).
-- Export is additionally capped at the narrower of the export and view scope
-- in AccessControlService.resolveExportEmployeeIds, so an export can never
-- reach a record the user could not already view.
--
-- Customer and follow-up export (CUSTOMER_EXPORT / FOLLOWUP_EXPORT) and every
-- *_DELETE permission are deliberately NOT touched. No employee row, role row
-- or existing grant is modified; re-running is a no-op.

INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT v.role_id, ex.id, v.scope
FROM role_permissions v
JOIN permissions pv ON pv.id = v.permission_id AND pv.code = 'LEAD_VIEW'
JOIN permissions ex ON ex.code = 'LEAD_EXPORT'
WHERE NOT EXISTS (
    SELECT 1 FROM role_permissions x
    WHERE x.role_id = v.role_id AND x.permission_id = ex.id
);

INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT v.role_id, ex.id, v.scope
FROM role_permissions v
JOIN permissions pv ON pv.id = v.permission_id AND pv.code = 'OPPORTUNITY_VIEW'
JOIN permissions ex ON ex.code = 'OPPORTUNITY_EXPORT'
WHERE NOT EXISTS (
    SELECT 1 FROM role_permissions x
    WHERE x.role_id = v.role_id AND x.permission_id = ex.id
);
