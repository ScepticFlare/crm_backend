-- Bulk email support. Purely additive and safe to re-run:
--  * email_send_log: one row per (batch, record) so a repeated submission or a
--    retry can never send the same recipient twice, and an ambiguous outcome is
--    recorded as UNKNOWN instead of being retried. No foreign keys on purpose.
--  * An editable AMC (Annual Maintenance Contract) email template, created only
--    if no AMC template exists yet, so it never overwrites an admin's own copy.
--    It deliberately contains NO prices, coverage, warranty/service terms or
--    validity - an admin must fill in the clearly marked [[...]] section, and
--    the application refuses to send a message that still contains one.

CREATE TABLE IF NOT EXISTS email_send_log (
    id                  BIGSERIAL PRIMARY KEY,
    batch_id            VARCHAR(64)  NOT NULL,
    target              VARCHAR(20)  NOT NULL,
    target_id           BIGINT       NOT NULL,
    record_label        VARCHAR(255) NULL,
    recipient_email     VARCHAR(255) NOT NULL,
    status              VARCHAR(20)  NOT NULL,
    detail              VARCHAR(500) NULL,
    template_id         BIGINT       NULL,
    sent_by_employee_id BIGINT       NULL,
    created_at          TIMESTAMP    NOT NULL,
    updated_at          TIMESTAMP    NOT NULL,
    CONSTRAINT uq_email_send_log_batch_record UNIQUE (batch_id, target, target_id)
);

CREATE INDEX IF NOT EXISTS idx_email_send_log_batch_email ON email_send_log (batch_id, recipient_email);

INSERT INTO email_templates
    (name, subject, body, type, created_by_name, is_active, is_default, created_at, updated_at)
SELECT
    'AMC Offer',
    'Annual Maintenance Contract (AMC) for your Compact Systems equipment',
    E'Dear {{contactPerson}},\n\nWe hope everything is running well at {{companyName}}.\n\nWe would like to tell you about an Annual Maintenance Contract (AMC) for the Compact Systems equipment you use.\n\n[[ADMIN: describe the AMC offer here - what is covered, visit frequency, pricing and offer validity. Remove this entire section before the template is used.]]\n\nIf you would like to know more or would like us to arrange a visit, please reply to this email and we will be happy to help.\n\nRegards,\nCompact Systems',
    'AMC',
    'System',
    TRUE,
    TRUE,
    NOW(),
    NOW()
WHERE NOT EXISTS (SELECT 1 FROM email_templates WHERE type = 'AMC');
