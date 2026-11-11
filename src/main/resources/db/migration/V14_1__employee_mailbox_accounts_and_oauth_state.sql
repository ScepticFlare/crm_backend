-- Employee-owned mailboxes (Gmail / Microsoft 365) replace the shared Brevo
-- account for every outbound CRM email. Purely additive and safe to re-run
-- (IF NOT EXISTS guards): no existing row is changed or deleted, so all bulk
-- email batches, the send log and the do-not-email list are kept as they are.
--
--  * email_mailbox_account: at most one connected mailbox per employee. Stores
--    only the mailbox identity (address, provider account id, Microsoft tenant),
--    the granted scopes and the AES-GCM ENCRYPTED refresh token. Access tokens
--    are never stored. Deleted with the employee (ON DELETE CASCADE) - a
--    credential must never outlive the person it belongs to. A mailbox address
--    and a provider account can each belong to only one employee.
--  * email_oauth_state: pending "connect my mailbox" authorizations. Only the
--    SHA-256 of the OAuth state is stored; single use, expires after minutes.
--  * email_send_log / email_bulk_batch: which mailbox sent (or will send) each
--    message, for the history view and the per-mailbox daily limit. Nullable:
--    rows written before this migration were sent through Brevo and stay NULL.

CREATE TABLE IF NOT EXISTS email_mailbox_account (
    id                       BIGSERIAL PRIMARY KEY,
    employee_id              BIGINT        NOT NULL REFERENCES employees (id) ON DELETE CASCADE,
    provider                 VARCHAR(20)   NOT NULL,
    email_address            VARCHAR(254)  NOT NULL,
    provider_subject         VARCHAR(255)  NOT NULL,
    provider_tenant_id       VARCHAR(64)   NULL,
    granted_scopes           VARCHAR(1000) NULL,
    refresh_token_encrypted  TEXT          NULL,
    status                   VARCHAR(20)   NOT NULL,
    status_detail            VARCHAR(300)  NULL,
    connected_at             TIMESTAMP     NOT NULL,
    last_used_at             TIMESTAMP     NULL,
    updated_at               TIMESTAMP     NOT NULL,
    CONSTRAINT uq_email_mailbox_account_employee UNIQUE (employee_id),
    CONSTRAINT uq_email_mailbox_account_subject UNIQUE (provider, provider_subject),
    CONSTRAINT uq_email_mailbox_account_email UNIQUE (email_address),
    CONSTRAINT chk_email_mailbox_account_provider CHECK (provider IN ('GOOGLE', 'MICROSOFT')),
    CONSTRAINT chk_email_mailbox_account_status CHECK (status IN ('CONNECTED', 'NEEDS_REAUTH'))
);

CREATE TABLE IF NOT EXISTS email_oauth_state (
    id                       BIGSERIAL PRIMARY KEY,
    state_hash               VARCHAR(64)  NOT NULL,
    employee_id              BIGINT       NOT NULL REFERENCES employees (id) ON DELETE CASCADE,
    provider                 VARCHAR(20)  NOT NULL,
    code_verifier_encrypted  VARCHAR(500) NOT NULL,
    nonce                    VARCHAR(100) NOT NULL,
    created_at               TIMESTAMP    NOT NULL,
    expires_at               TIMESTAMP    NOT NULL,
    CONSTRAINT uq_email_oauth_state_hash UNIQUE (state_hash)
);

CREATE INDEX IF NOT EXISTS idx_email_oauth_state_expires ON email_oauth_state (expires_at);

ALTER TABLE email_send_log ADD COLUMN IF NOT EXISTS sender_provider VARCHAR(20) NULL;
ALTER TABLE email_send_log ADD COLUMN IF NOT EXISTS sender_email VARCHAR(254) NULL;

CREATE INDEX IF NOT EXISTS idx_email_send_log_sender_updated ON email_send_log (sender_email, updated_at);

ALTER TABLE email_bulk_batch ADD COLUMN IF NOT EXISTS sender_provider VARCHAR(20) NULL;
ALTER TABLE email_bulk_batch ADD COLUMN IF NOT EXISTS sender_email VARCHAR(254) NULL;
