-- Five-main-stage workflow, completed: the remaining non-main stages are
-- retired (approved mapping: UNRESPONSIVE -> DROPPED, INVALID -> LOST, the old
-- QUOTATION SENT / NEGOTIATION stages -> IN PROGRESS with that sub-status,
-- custom stages -> IN PROGRESS, with the matching sub-status only when the
-- name clearly means quotation or negotiation).
--
-- Purely additive. Adds the provenance kept on each moved Opportunity: the
-- stage it was moved out of (type - null for a custom stage - and its name as
-- it was then) and when. NULL for every Opportunity that was never in such a
-- stage. The Unresponsive / Invalid pages list Opportunities by this
-- provenance once they are in DROPPED / LOST.
--
-- No row is modified here. The move itself is done once at startup by
-- service.LegacyStageRetirement, after SalesStageService.ensureCoreStages has
-- resolved the stable stage types (stages are found by
-- sales_stages.stage_type, never by their editable names). It changes only
-- sales_stage_id, sub_status and these columns, writes a history entry per
-- Opportunity, and keeps every stage row (inactive) for referential integrity
-- and history.
ALTER TABLE opportunities ADD COLUMN IF NOT EXISTS retired_stage_type VARCHAR(30) NULL;
ALTER TABLE opportunities ADD COLUMN IF NOT EXISTS retired_stage_name VARCHAR(255) NULL;
ALTER TABLE opportunities ADD COLUMN IF NOT EXISTS retired_stage_moved_at TIMESTAMP NULL;
