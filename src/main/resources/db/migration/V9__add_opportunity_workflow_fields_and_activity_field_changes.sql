-- Sales workflow improvements (see service.OpportunityWorkflow /
-- OpportunityService). Purely additive: every new column is nullable with
-- no default, no existing row is touched, and no existing constraint is
-- altered, so historical Leads / Opportunities / activity entries are
-- unaffected and the application still starts with old records present.

-- 1. Opportunity workflow fields.
--    sub_status: QUOTATION_SENT / NEGOTIATION, only meaningful while the
--      Sales Stage is "IN PROGRESS" (enforced in OpportunityService).
--    lost_reason: free text, captured when an Opportunity is marked LOST.
--      Historical LOST Opportunities keep NULL - no reason is fabricated.
--    postponed_review_date: OPTIONAL date to revisit a POSTPONED Opportunity.
--    postponed_reminder_acknowledged_for: the review date whose reminder the
--      employee has acknowledged (so the OK survives a page refresh, and
--      changing the review date re-arms the reminder). Acknowledging never
--      changes the stage.
ALTER TABLE opportunities ADD COLUMN IF NOT EXISTS sub_status VARCHAR(30) NULL;
ALTER TABLE opportunities ADD COLUMN IF NOT EXISTS lost_reason TEXT NULL;
ALTER TABLE opportunities ADD COLUMN IF NOT EXISTS postponed_review_date DATE NULL;
ALTER TABLE opportunities ADD COLUMN IF NOT EXISTS postponed_reminder_acknowledged_for DATE NULL;

-- 2. Field-level history. The existing activity_log only has a free-text
--    description, which cannot represent "field X changed from A to B".
--    These nullable columns let a row record exactly that; every older row
--    simply leaves them NULL and keeps rendering from description.
ALTER TABLE activity_log ADD COLUMN IF NOT EXISTS field_name VARCHAR(100) NULL;
ALTER TABLE activity_log ADD COLUMN IF NOT EXISTS old_value TEXT NULL;
ALTER TABLE activity_log ADD COLUMN IF NOT EXISTS new_value TEXT NULL;

-- 3. Stable stage identity. sales_stages.name is an editable display name
--    (Admins may rename any stage, including WON / LOST / POSTPONED / IN
--    PROGRESS), so business rules key off this nullable type instead.
--    Left NULL here on purpose: which existing stage is which core stage is
--    decided once, unambiguously, by service.SalesStageService.ensureCoreStages
--    at startup (it never guesses when several stages could match, and never
--    touches an Opportunity). No existing row is modified by this migration.
ALTER TABLE sales_stages ADD COLUMN IF NOT EXISTS stage_type VARCHAR(30) NULL;

-- 4. One Opportunity per Lead, enforced by the database as well as the
--    service (closes the race where two simultaneous conversions both pass
--    the "already converted?" check). Created only when no Lead already has
--    several Opportunities, so existing data can never make the migration
--    fail or be altered; otherwise it is skipped with a notice.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM opportunities WHERE lead_id IS NOT NULL
        GROUP BY lead_id HAVING COUNT(*) > 1
    ) THEN
        RAISE NOTICE 'uq_opportunities_lead_id NOT created: some leads already have more than one opportunity';
    ELSE
        CREATE UNIQUE INDEX IF NOT EXISTS uq_opportunities_lead_id ON opportunities (lead_id);
    END IF;
END
$$;
