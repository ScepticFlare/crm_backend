-- Bulk WhatsApp, part 1: CRM-managed message templates, per-number consent and
-- its audit history, and the WhatsApp permissions. Purely additive and safe to
-- re-run (IF NOT EXISTS / NOT EXISTS guards). No existing table or row is
-- changed except granting the new permissions. No lead is opted in: a number
-- without a consent row is never messaged in bulk.
--
--  * whatsapp_templates: reusable message drafts authored by admins INSIDE the
--    CRM (not WhatsApp/Meta-approved templates). `version` increases on every
--    body change; a bulk batch pins the body + version it was created with.
--  * whatsapp_consent: one row per NORMALIZED number (digits incl. country code,
--    e.g. 919876543210). OPTED_OUT always wins over any opt-in; several leads
--    sharing a number share its consent.
--  * whatsapp_consent_audit: append-only history of every opt-in / opt-out.
--  * WHATSAPP_SEND: scoped exactly like EMAIL_SEND (ADMIN ALL, MANAGER TEAM,
--    EMPLOYEE OWN). WHATSAPP_TEMPLATE_MANAGE and WHATSAPP_CONSENT_MANAGE: ADMIN only.
-- No foreign keys, like email_send_log / email_bulk_batch (V12/V13).

CREATE TABLE IF NOT EXISTS whatsapp_templates (
    id                     BIGSERIAL PRIMARY KEY,
    name                   VARCHAR(150) NOT NULL,
    description            VARCHAR(500) NULL,
    body                   TEXT         NOT NULL,
    version                INTEGER      NOT NULL DEFAULT 1,
    is_active              BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by_employee_id BIGINT       NULL,
    created_by_name        VARCHAR(255) NULL,
    updated_by_employee_id BIGINT       NULL,
    created_at             TIMESTAMP    NOT NULL,
    updated_at             TIMESTAMP    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_whatsapp_templates_active ON whatsapp_templates (is_active, name);

CREATE TABLE IF NOT EXISTS whatsapp_consent (
    id                      BIGSERIAL PRIMARY KEY,
    phone_e164              VARCHAR(20)  NOT NULL,
    status                  VARCHAR(20)  NOT NULL,
    source                  VARCHAR(50)  NOT NULL,
    evidence                VARCHAR(500) NULL,
    consented_at            TIMESTAMP    NOT NULL,
    lead_id                 BIGINT       NULL,
    opportunity_id          BIGINT       NULL,
    recorded_by_employee_id BIGINT       NULL,
    created_at              TIMESTAMP    NOT NULL,
    updated_at              TIMESTAMP    NOT NULL,
    CONSTRAINT uq_whatsapp_consent_phone UNIQUE (phone_e164),
    CONSTRAINT chk_whatsapp_consent_status CHECK (status IN ('OPTED_IN', 'OPTED_OUT'))
);

CREATE TABLE IF NOT EXISTS whatsapp_consent_audit (
    id                       BIGSERIAL PRIMARY KEY,
    phone_e164               VARCHAR(20)  NOT NULL,
    previous_status          VARCHAR(20)  NULL,
    new_status               VARCHAR(20)  NOT NULL,
    source                   VARCHAR(50)  NOT NULL,
    evidence                 VARCHAR(500) NULL,
    consented_at             TIMESTAMP    NULL,
    lead_id                  BIGINT       NULL,
    opportunity_id           BIGINT       NULL,
    import_reference         VARCHAR(64)  NULL,
    performed_by_employee_id BIGINT       NULL,
    performed_by_name        VARCHAR(255) NULL,
    performed_at             TIMESTAMP    NOT NULL,
    CONSTRAINT chk_whatsapp_consent_audit_status CHECK (new_status IN ('OPTED_IN', 'OPTED_OUT'))
);

CREATE INDEX IF NOT EXISTS idx_whatsapp_consent_audit_phone ON whatsapp_consent_audit (phone_e164, performed_at);
CREATE INDEX IF NOT EXISTS idx_whatsapp_consent_audit_lead ON whatsapp_consent_audit (lead_id);

INSERT INTO permissions (code)
SELECT c.code
FROM (VALUES ('WHATSAPP_SEND'), ('WHATSAPP_TEMPLATE_MANAGE'), ('WHATSAPP_CONSENT_MANAGE')) AS c(code)
WHERE NOT EXISTS (SELECT 1 FROM permissions p WHERE p.code = c.code);

-- ADMIN: all three, ALL scope.
INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT r.id, p.id, 'ALL'
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'ADMIN'
  AND p.code IN ('WHATSAPP_SEND', 'WHATSAPP_TEMPLATE_MANAGE', 'WHATSAPP_CONSENT_MANAGE')
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);

-- MANAGER: send within the team.
INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT r.id, p.id, 'TEAM'
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'MANAGER'
  AND p.code = 'WHATSAPP_SEND'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);

-- EMPLOYEE: send to own records.
INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT r.id, p.id, 'OWN'
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'EMPLOYEE'
  AND p.code = 'WHATSAPP_SEND'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);
