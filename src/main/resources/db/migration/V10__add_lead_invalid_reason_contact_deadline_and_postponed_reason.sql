-- Lead workflow simplification (NEW / VALID / INVALID) and Director-requested
-- fields. Purely additive: every column is nullable with no default and no
-- existing row is modified, so historical leads / opportunities load exactly
-- as before.
--
-- NOTE: LeadStatus gains the VALID value. leads.lead_status is guarded by a
-- check constraint that config.LeadStatusConstraintSync rebuilds from the
-- LeadStatus enum on every startup (keeping every legacy value), so no
-- constraint change is needed here.

-- Why a lead was marked INVALID. NULL for every historical INVALID lead.
ALTER TABLE leads ADD COLUMN IF NOT EXISTS invalid_reason TEXT NULL;

-- "Contact by" date for new leads. NULL for historical leads (never flagged).
ALTER TABLE leads ADD COLUMN IF NOT EXISTS contact_deadline DATE NULL;

-- Optional reason a POSTPONED opportunity was deferred (the review date stays
-- optional, as previously decided). NULL for every historical record.
ALTER TABLE opportunities ADD COLUMN IF NOT EXISTS postponed_reason TEXT NULL;
