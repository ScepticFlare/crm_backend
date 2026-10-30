-- Opportunity-owned contact details and product/battery line items.
--
-- Until now an Opportunity had no contact, product or battery data of its own:
-- every screen and export read them live from the originating Lead. A change in
-- the customer's requirement after conversion could therefore not be recorded on
-- the Opportunity, and editing the Lead silently rewrote the Opportunity.
--
-- Purely additive and safe to re-run:
--  * three NULLable columns on opportunities, no default;
--  * two new line-item tables shaped exactly like lead_products / lead_batteries;
--  * a one-time backfill that COPIES each existing Opportunity's Lead values into
--    them, so every report shows exactly what it showed before this migration.
--    Nothing is invented: an Opportunity whose Lead has no battery gets no battery
--    row, a NULL Lead email stays NULL. No existing row in any other table is
--    modified, and leads keep their own values (traceability).
--  * the backfill only touches Opportunities that have no line items / contact of
--    their own yet, so re-running it never overwrites a value edited since.

ALTER TABLE opportunities ADD COLUMN IF NOT EXISTS contact_person VARCHAR(255) NULL;
ALTER TABLE opportunities ADD COLUMN IF NOT EXISTS contact_phone VARCHAR(255) NULL;
ALTER TABLE opportunities ADD COLUMN IF NOT EXISTS contact_email VARCHAR(255) NULL;

CREATE TABLE IF NOT EXISTS opportunity_products (
    id             BIGSERIAL PRIMARY KEY,
    opportunity_id BIGINT  NOT NULL REFERENCES opportunities (id) ON DELETE CASCADE,
    product_id     BIGINT  NOT NULL REFERENCES products (id),
    quantity       INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS opportunity_batteries (
    id             BIGSERIAL PRIMARY KEY,
    opportunity_id BIGINT  NOT NULL REFERENCES opportunities (id) ON DELETE CASCADE,
    battery_id     BIGINT  NOT NULL REFERENCES batteries (id),
    quantity       INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_opportunity_products_opportunity_id ON opportunity_products (opportunity_id);
CREATE INDEX IF NOT EXISTS idx_opportunity_products_product_id ON opportunity_products (product_id);
CREATE INDEX IF NOT EXISTS idx_opportunity_batteries_opportunity_id ON opportunity_batteries (opportunity_id);
CREATE INDEX IF NOT EXISTS idx_opportunity_batteries_battery_id ON opportunity_batteries (battery_id);

-- Backfill contact details (only where the Opportunity has none of its own yet).
UPDATE opportunities o
SET contact_person = l.contact_person,
    contact_phone  = l.phone,
    contact_email  = l.email
FROM leads l
WHERE o.lead_id = l.id
  AND o.contact_person IS NULL
  AND o.contact_phone IS NULL
  AND o.contact_email IS NULL;

-- Backfill line items (only for Opportunities that have none of their own yet).
-- Rows with a NULL product/battery or quantity on the Lead side are skipped
-- rather than guessed.
INSERT INTO opportunity_products (opportunity_id, product_id, quantity)
SELECT o.id, lp.product_id, lp.quantity
FROM opportunities o
JOIN lead_products lp ON lp.lead_id = o.lead_id
WHERE lp.product_id IS NOT NULL
  AND lp.quantity IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM opportunity_products op WHERE op.opportunity_id = o.id)
ORDER BY o.id, lp.id;

INSERT INTO opportunity_batteries (opportunity_id, battery_id, quantity)
SELECT o.id, lb.battery_id, lb.quantity
FROM opportunities o
JOIN lead_batteries lb ON lb.lead_id = o.lead_id
WHERE lb.battery_id IS NOT NULL
  AND lb.quantity IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM opportunity_batteries ob WHERE ob.opportunity_id = o.id)
ORDER BY o.id, lb.id;
