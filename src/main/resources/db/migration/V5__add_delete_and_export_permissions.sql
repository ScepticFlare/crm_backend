-- Splits DELETE and EXPORT out of the existing *_MANAGE permission
-- (LEAD_MANAGE/OPPORTUNITY_MANAGE/CUSTOMER_MANAGE/FOLLOWUP_MANAGE) into
-- their own admin-only permission codes (see
-- security.AccessControlService.LEAD_DELETE etc). Business rule: deleting a
-- CRM record and exporting CRM data are administrative capabilities - only
-- ADMIN may perform them, regardless of the TEAM/OWN scope MANAGER/EMPLOYEE
-- still have for *_MANAGE (create/update/reassign are unaffected).
--
-- Only ADMIN is granted these new permissions (ALL scope) - MANAGER and
-- EMPLOYEE deliberately get no row in role_permissions for them, so
-- AccessControlService.hasPermission/resolveScope returns null and every
-- delete/export call site 403s for them via assertCanAccess/requirePermission.

INSERT INTO permissions (code) VALUES
    ('LEAD_DELETE'), ('OPPORTUNITY_DELETE'), ('CUSTOMER_DELETE'), ('FOLLOWUP_DELETE'),
    ('LEAD_EXPORT'), ('OPPORTUNITY_EXPORT'), ('CUSTOMER_EXPORT'), ('FOLLOWUP_EXPORT');

INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT r.id, p.id, 'ALL'
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'ADMIN'
  AND p.code IN (
      'LEAD_DELETE', 'OPPORTUNITY_DELETE', 'CUSTOMER_DELETE', 'FOLLOWUP_DELETE',
      'LEAD_EXPORT', 'OPPORTUNITY_EXPORT', 'CUSTOMER_EXPORT', 'FOLLOWUP_EXPORT'
  );
