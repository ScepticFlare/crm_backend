-- Introduces the Lead email feature: reusable EmailTemplate records,
-- Document records (product brochures / company profile / general files,
-- metadata only - the actual bytes live on local disk via
-- service.DocumentStorageService, see application.properties
-- crm.documents.storage-path), and the permission codes governing who can
-- send email vs manage the template/document library (see
-- security.AccessControlService).

-- 1. Reusable email templates (Product Brochure / Keep in Touch). Shared
--    org-wide, not owned per-employee - created_by_employee_id is metadata
--    (who authored it), not an ownership/visibility scope. Nullable with ON
--    DELETE SET NULL for the same reason as activity_log.employee_id: a
--    required FK would make any employee who ever created a template
--    undeletable. created_by_name is a snapshot for the same readability
--    reason activity_log.employee_name is.
CREATE TABLE email_templates (
    id                     BIGSERIAL PRIMARY KEY,
    name                   VARCHAR(150) NOT NULL,
    subject                VARCHAR(255) NOT NULL,
    body                   TEXT NOT NULL,
    type                   VARCHAR(20) NOT NULL,
    created_by_employee_id BIGINT NULL REFERENCES employees(id) ON DELETE SET NULL,
    created_by_name        VARCHAR(255) NOT NULL,
    is_active              BOOLEAN NOT NULL DEFAULT TRUE,
    -- At most one active template per type is expected to have this TRUE -
    -- enforced in application code (EmailTemplateService), not a DB
    -- constraint, so an admin can freely repoint the default without a
    -- transactional two-step. The composer prefills from this row when the
    -- user hasn't picked a template yet.
    is_default             BOOLEAN NOT NULL DEFAULT FALSE,
    created_at             TIMESTAMP NOT NULL,
    updated_at             TIMESTAMP NOT NULL
);

CREATE INDEX idx_email_templates_type_active ON email_templates (type, is_active);

-- Seed one default template per type, matching the example copy from the
-- business requirement, so the composer has something sensible to prefill
-- from on a fresh install. Not privileged in any way - an admin can edit,
-- deactivate, or repoint is_default to a different template at any time via
-- the normal EmailTemplate CRUD endpoints.
INSERT INTO email_templates
    (name, subject, body, type, created_by_name, is_active, is_default, created_at, updated_at)
VALUES
    (
        'Default Product Brochure',
        'Compact Systems – Product Information',
        E'Dear {{contactPerson}},\n\nThank you for your interest in our {{interestedProduct}}.\nPlease find the relevant product information attached for your reference.\n\nPlease feel free to contact us if you require any further information.\n\nRegards,\nCompact Systems',
        'PRODUCT_BROCHURE',
        'System',
        TRUE,
        TRUE,
        NOW(),
        NOW()
    ),
    (
        'Default Keep in Touch',
        'Greetings from Compact Systems',
        E'Dear {{contactPerson}},\n\nWe would like to stay connected with you and share a quick update from Compact Systems. Please find our company profile attached for your reference.\n\nWe look forward to staying in touch and would be happy to assist you whenever you have a requirement.\n\nRegards,\nCompact Systems',
        'KEEP_IN_TOUCH',
        'System',
        TRUE,
        TRUE,
        NOW(),
        NOW()
    );

-- 2. Document metadata (brochures / company profile / general). product_id
--    links a PRODUCT_BROCHURE document to the Product it's for, so the
--    composer can auto-suggest the right brochure for a Lead's interested
--    product(s); NULL for COMPANY_PROFILE/GENERAL documents. ON DELETE SET
--    NULL rather than blocking product deletion on an attached brochure.
CREATE TABLE documents (
    id                      BIGSERIAL PRIMARY KEY,
    file_name               VARCHAR(255) NOT NULL,
    stored_file_name        VARCHAR(255) NOT NULL UNIQUE,
    content_type            VARCHAR(100) NOT NULL,
    file_size_bytes          BIGINT NOT NULL,
    category                VARCHAR(20) NOT NULL,
    product_id              BIGINT NULL REFERENCES products(id) ON DELETE SET NULL,
    uploaded_by_employee_id BIGINT NULL REFERENCES employees(id) ON DELETE SET NULL,
    uploaded_by_name        VARCHAR(255) NOT NULL,
    is_active               BOOLEAN NOT NULL DEFAULT TRUE,
    created_at              TIMESTAMP NOT NULL
);

CREATE INDEX idx_documents_category_active ON documents (category, is_active);
CREATE INDEX idx_documents_product ON documents (product_id);

-- 3. Permission codes.
--
--    EMAIL_SEND governs both the individual Send Email flow and being a
--    valid target of the bulk Keep in Touch flow - same ALL/TEAM/OWN shape
--    as LEAD_MANAGE, so an employee can only email a Lead they're already
--    authorized to manage. ADMIN=ALL, MANAGER=TEAM, EMPLOYEE=OWN.
--
--    EMAIL_TEMPLATE_MANAGE / DOCUMENT_MANAGE govern create/update/delete of
--    the shared template and document library - ADMIN-only (ALL scope),
--    mirroring the existing Product/Industry master-data pattern where
--    mutation is admin-gated but listing is open to any authenticated user.
INSERT INTO permissions (code) VALUES
    ('EMAIL_SEND'), ('EMAIL_TEMPLATE_MANAGE'), ('DOCUMENT_MANAGE');

INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT r.id, p.id, 'ALL'
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'ADMIN'
  AND p.code IN ('EMAIL_SEND', 'EMAIL_TEMPLATE_MANAGE', 'DOCUMENT_MANAGE');

INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT r.id, p.id, 'TEAM'
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'MANAGER' AND p.code = 'EMAIL_SEND';

INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT r.id, p.id, 'OWN'
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'EMPLOYEE' AND p.code = 'EMAIL_SEND';

-- MANAGER/EMPLOYEE deliberately get no row for EMAIL_TEMPLATE_MANAGE or
-- DOCUMENT_MANAGE, so AccessControlService.hasPermission/resolveScope
-- returns null and every template/document mutation 403s for them via
-- requirePermission - identical mechanism to how V5 kept LEAD_DELETE/
-- LEAD_EXPORT admin-only.
