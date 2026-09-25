-- Foreign-key / RBAC-scope / default-sort columns that every list endpoint
-- filters or joins on but that ddl-auto never indexed (Postgres does not
-- automatically index a foreign key column, only the primary key and
-- explicit unique constraints). At today's row counts (~150 leads) these
-- don't move the needle, but every list query below filters or joins on
-- them, so they're correct hygiene and will matter as the tables grow -
-- see specification.LeadSpecifications.ownerIn/fetchAssociations and the
-- equivalent Opportunity/Customer/FollowUp specifications.

-- Leads: RBAC scope (assigned_employee_id), status-bucket routes
-- (lead_status), and the default list sort (created_at).
CREATE INDEX IF NOT EXISTS idx_leads_assigned_employee_id ON leads (assigned_employee_id);
CREATE INDEX IF NOT EXISTS idx_leads_lead_status ON leads (lead_status);
CREATE INDEX IF NOT EXISTS idx_leads_created_at ON leads (created_at);
CREATE INDEX IF NOT EXISTS idx_leads_industry_id ON leads (industry_id);
CREATE INDEX IF NOT EXISTS idx_leads_lead_source_id ON leads (lead_source_id);

-- Lead line items: joined for every lead list/detail fetch.
CREATE INDEX IF NOT EXISTS idx_lead_products_lead_id ON lead_products (lead_id);
CREATE INDEX IF NOT EXISTS idx_lead_products_product_id ON lead_products (product_id);
CREATE INDEX IF NOT EXISTS idx_lead_batteries_lead_id ON lead_batteries (lead_id);
CREATE INDEX IF NOT EXISTS idx_lead_batteries_battery_id ON lead_batteries (battery_id);

-- Opportunities: RBAC scope is via lead.assigned_employee_id (already
-- indexed above); lead_id/sales_stage_id back the fetch-join and
-- stage-bucket routes.
CREATE INDEX IF NOT EXISTS idx_opportunities_lead_id ON opportunities (lead_id);
CREATE INDEX IF NOT EXISTS idx_opportunities_sales_stage_id ON opportunities (sales_stage_id);
CREATE INDEX IF NOT EXISTS idx_opportunities_created_at ON opportunities (created_at);

-- Customers: RBAC scope + the opportunity fetch-join.
CREATE INDEX IF NOT EXISTS idx_customers_assigned_employee_id ON customers (assigned_employee_id);
CREATE INDEX IF NOT EXISTS idx_customers_opportunity_id ON customers (opportunity_id);

-- Follow-ups: RBAC scope is via lead/opportunity.lead (already indexed);
-- these back the FollowUp-specific filters/fetch-joins and the Dashboard's
-- upcoming-follow-ups query (status + scheduled_date).
CREATE INDEX IF NOT EXISTS idx_followups_lead_id ON followups (lead_id);
CREATE INDEX IF NOT EXISTS idx_followups_opportunity_id ON followups (opportunity_id);
CREATE INDEX IF NOT EXISTS idx_followups_employee_id ON followups (employee_id);
CREATE INDEX IF NOT EXISTS idx_followups_activity_type_id ON followups (activity_type_id);
CREATE INDEX IF NOT EXISTS idx_followups_status_scheduled_date ON followups (status, scheduled_date);

-- Employees: self-referential manager lookup (AccessControlService.teamMemberIds
-- / EmployeeRepository.findByManagerId) and the role join on every login.
CREATE INDEX IF NOT EXISTS idx_employees_manager_id ON employees (manager_id);
CREATE INDEX IF NOT EXISTS idx_employees_role_id ON employees (role_id);
