-- Bulk WhatsApp, part 3: Meta WhatsApp Cloud API support. Purely additive,
-- nullable columns; safe to re-run (IF NOT EXISTS). Existing rows, batches and
-- the WAHA provider keep working unchanged.
--
--  * whatsapp_templates.meta_*: links a CRM template to a template APPROVED in
--    Meta's WhatsApp Manager - its exact name, its language code and the CRM
--    merge-field keys (comma-separated, in order) that fill the approved
--    template's body parameters {{1}}, {{2}}, ... The CRM `body` stays the text
--    the CRM previews; with the Meta provider only linked templates can be sent.
--    The CRM cannot see Meta's approval state: an admin links a template only
--    after Meta has approved it (a non-approved name is rejected by Meta, error 132001).
--  * whatsapp_bulk_batch.meta_*: the same three values PINNED when a batch is
--    created, like body / template_version, so later edits never change it.
--  * whatsapp_webhook_event.delivery_status: the status a Meta webhook reported
--    (SENT / DELIVERED / READ / FAILED). WAHA events keep using `ack`.

ALTER TABLE whatsapp_templates ADD COLUMN IF NOT EXISTS meta_template_name VARCHAR(512) NULL;
ALTER TABLE whatsapp_templates ADD COLUMN IF NOT EXISTS meta_language_code VARCHAR(15) NULL;
ALTER TABLE whatsapp_templates ADD COLUMN IF NOT EXISTS meta_body_params VARCHAR(1000) NULL;

ALTER TABLE whatsapp_bulk_batch ADD COLUMN IF NOT EXISTS meta_template_name VARCHAR(512) NULL;
ALTER TABLE whatsapp_bulk_batch ADD COLUMN IF NOT EXISTS meta_language_code VARCHAR(15) NULL;
ALTER TABLE whatsapp_bulk_batch ADD COLUMN IF NOT EXISTS meta_body_params VARCHAR(1000) NULL;

ALTER TABLE whatsapp_webhook_event ADD COLUMN IF NOT EXISTS delivery_status VARCHAR(20) NULL;
