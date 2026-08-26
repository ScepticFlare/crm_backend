-- Widens activity_log.description from VARCHAR(500) to TEXT.
--
-- Needed for the new Lead REMARKS_UPDATED activity entries (see
-- service.LeadService.updateLead / enums.ActivityAction) which copy
-- Lead.finalRemarks into this column in full - finalRemarks itself is an
-- unbounded TEXT column on leads, so the audit-trail copy of it must be
-- too, or long remarks would be silently truncated.
ALTER TABLE activity_log ALTER COLUMN description TYPE TEXT;
