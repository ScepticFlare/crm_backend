-- Bulk email hardening. Purely additive and safe to re-run (IF NOT EXISTS / NOT EXISTS
-- guards). Nothing here changes or deletes an existing row except granting one new
-- permission to ADMIN.
--
--  * email_bulk_batch: a SERVER-issued batch. It pins the owner, target, template, the
--    exact subject/body (plus a hash of them) and the ordered recipient record ids, so a
--    later chunk can only send a subset of the original list with the original content.
--    The unique batch_id is a server-generated UUID, which removes the old "client picks
--    the batch id" collision race. No foreign keys, like email_send_log.
--  * email_send_log index on (recipient_email, updated_at): the cross-batch "already
--    emailed recently" check.
--  * email_suppression: a do-not-email list keyed by the NORMALIZED ADDRESS (trimmed,
--    lower-case), not by lead/opportunity. No rows are seeded.
--  * email_suppression_audit: append-only history of every add and removal.
--  * EMAIL_SUPPRESSION_MANAGE permission: ADMIN only (ALL), the same shape V6 used for
--    EMAIL_TEMPLATE_MANAGE. MANAGER/EMPLOYEE get no row, so every suppression endpoint
--    403s for them.

CREATE TABLE IF NOT EXISTS email_bulk_batch (
    id                 BIGSERIAL PRIMARY KEY,
    batch_id           VARCHAR(64)  NOT NULL,
    owner_employee_id  BIGINT       NOT NULL,
    target             VARCHAR(20)  NOT NULL,
    template_id        BIGINT       NULL,
    subject            VARCHAR(255) NOT NULL,
    body               TEXT         NOT NULL,
    content_hash       VARCHAR(64)  NOT NULL,
    recipient_ids      TEXT         NOT NULL,
    recipient_count    INTEGER      NOT NULL,
    created_at         TIMESTAMP    NOT NULL,
    CONSTRAINT uq_email_bulk_batch_batch_id UNIQUE (batch_id)
);

CREATE INDEX IF NOT EXISTS idx_email_bulk_batch_owner ON email_bulk_batch (owner_employee_id, created_at);

CREATE INDEX IF NOT EXISTS idx_email_send_log_email_updated ON email_send_log (recipient_email, updated_at);

CREATE TABLE IF NOT EXISTS email_suppression (
    id                     BIGSERIAL PRIMARY KEY,
    email_normalized       VARCHAR(254) NOT NULL,
    reason                 VARCHAR(20)  NOT NULL,
    source                 VARCHAR(50)  NOT NULL,
    note                   VARCHAR(500) NULL,
    created_by_employee_id BIGINT       NULL,
    created_at             TIMESTAMP    NOT NULL,
    CONSTRAINT uq_email_suppression_email UNIQUE (email_normalized),
    CONSTRAINT chk_email_suppression_reason CHECK (reason IN ('OPT_OUT', 'BOUNCE', 'COMPLAINT', 'MANUAL'))
);

CREATE TABLE IF NOT EXISTS email_suppression_audit (
    id                      BIGSERIAL PRIMARY KEY,
    email_normalized        VARCHAR(254) NOT NULL,
    action                  VARCHAR(20)  NOT NULL,
    reason                  VARCHAR(20)  NULL,
    note                    VARCHAR(500) NULL,
    performed_by_employee_id BIGINT      NULL,
    performed_at            TIMESTAMP    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_email_suppression_audit_email ON email_suppression_audit (email_normalized);

INSERT INTO permissions (code)
SELECT 'EMAIL_SUPPRESSION_MANAGE'
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE code = 'EMAIL_SUPPRESSION_MANAGE');

INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT r.id, p.id, 'ALL'
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'ADMIN'
  AND p.code = 'EMAIL_SUPPRESSION_MANAGE'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);
