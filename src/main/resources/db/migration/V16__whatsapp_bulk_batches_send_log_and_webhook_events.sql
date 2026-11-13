-- Bulk WhatsApp, part 2: server-issued batches, the per-recipient send log and
-- webhook-event idempotency storage. Purely additive and safe to re-run. Same
-- design as the bulk email tables (V12/V13), no foreign keys.
--
--  * whatsapp_bulk_batch: pins owner, target, template id + VERSION + the exact
--    body snapshot (+ hash) and the ordered recipient record ids. Editing the
--    template afterwards never changes what an existing batch sends.
--  * whatsapp_send_log: one row per (batch, record). The UNIQUE key is the claim
--    taken BEFORE the provider is called, so a double click, a repeated request
--    or a retried chunk can never message the same recipient twice.
--    provider_message_id (UNIQUE when present) links delivery webhooks to the row;
--    delivery_status only ever moves forward (see WhatsAppDeliveryStatus).
--  * whatsapp_webhook_event: one row per distinct provider event (event_key is
--    UNIQUE), so a redelivered webhook is recognised and ignored. Stores ids and
--    statuses only - never inbound message text.

CREATE TABLE IF NOT EXISTS whatsapp_bulk_batch (
    id                 BIGSERIAL PRIMARY KEY,
    batch_id           VARCHAR(64)  NOT NULL,
    owner_employee_id  BIGINT       NOT NULL,
    target             VARCHAR(20)  NOT NULL,
    template_id        BIGINT       NOT NULL,
    template_version   INTEGER      NOT NULL,
    template_name      VARCHAR(150) NOT NULL,
    body               TEXT         NOT NULL,
    content_hash       VARCHAR(64)  NOT NULL,
    recipient_ids      TEXT         NOT NULL,
    recipient_count    INTEGER      NOT NULL,
    created_at         TIMESTAMP    NOT NULL,
    CONSTRAINT uq_whatsapp_bulk_batch_batch_id UNIQUE (batch_id),
    CONSTRAINT chk_whatsapp_bulk_batch_target CHECK (target IN ('LEAD', 'OPPORTUNITY'))
);

CREATE INDEX IF NOT EXISTS idx_whatsapp_bulk_batch_owner ON whatsapp_bulk_batch (owner_employee_id, created_at);

CREATE TABLE IF NOT EXISTS whatsapp_send_log (
    id                  BIGSERIAL PRIMARY KEY,
    batch_id            VARCHAR(64)  NOT NULL,
    target              VARCHAR(20)  NOT NULL,
    target_id           BIGINT       NOT NULL,
    record_label        VARCHAR(255) NULL,
    phone_e164          VARCHAR(20)  NOT NULL,
    status              VARCHAR(20)  NOT NULL,
    detail              VARCHAR(500) NULL,
    provider_message_id VARCHAR(200) NULL,
    delivery_status     VARCHAR(20)  NULL,
    delivery_updated_at TIMESTAMP    NULL,
    template_id         BIGINT       NULL,
    template_version    INTEGER      NULL,
    sent_by_employee_id BIGINT       NULL,
    created_at          TIMESTAMP    NOT NULL,
    updated_at          TIMESTAMP    NOT NULL,
    CONSTRAINT uq_whatsapp_send_log_batch_record UNIQUE (batch_id, target, target_id),
    CONSTRAINT uq_whatsapp_send_log_provider_message UNIQUE (provider_message_id),
    CONSTRAINT chk_whatsapp_send_log_status CHECK (status IN ('PENDING', 'ACCEPTED', 'FAILED', 'UNKNOWN')),
    CONSTRAINT chk_whatsapp_send_log_delivery
        CHECK (delivery_status IS NULL OR delivery_status IN ('PENDING', 'SENT', 'FAILED', 'DELIVERED', 'READ'))
);

CREATE INDEX IF NOT EXISTS idx_whatsapp_send_log_batch_phone ON whatsapp_send_log (batch_id, phone_e164);
CREATE INDEX IF NOT EXISTS idx_whatsapp_send_log_phone_updated ON whatsapp_send_log (phone_e164, updated_at);

CREATE TABLE IF NOT EXISTS whatsapp_webhook_event (
    id                  BIGSERIAL PRIMARY KEY,
    event_key           VARCHAR(300) NOT NULL,
    event_type          VARCHAR(40)  NOT NULL,
    provider_message_id VARCHAR(200) NULL,
    ack                 INTEGER      NULL,
    session_name        VARCHAR(100) NULL,
    applied             BOOLEAN      NOT NULL DEFAULT FALSE,
    received_at         TIMESTAMP    NOT NULL,
    CONSTRAINT uq_whatsapp_webhook_event_key UNIQUE (event_key)
);

CREATE INDEX IF NOT EXISTS idx_whatsapp_webhook_event_message ON whatsapp_webhook_event (provider_message_id, applied);
