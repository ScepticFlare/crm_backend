# Follow-up relationships: which record a follow-up belongs to

## The model

A follow-up (`followups`) has two optional foreign keys:

| `lead_id` | `opportunity_id` | Meaning | Created by |
|---|---|---|---|
| set | NULL | **Lead follow-up**: a contact attempt on a lead | Follow-up form, Lead Details |
| NULL | set | **Opportunity follow-up**, e.g. the mandatory next action | Lead→Opportunity conversion, follow-up form, Opportunity Details |
| set | set | Both, which must be the **same** record (the opportunity converted from that lead) | API callers naming both |

Every opportunity is created from a lead (`OpportunityService.convertLeadToOpportunity`). An opportunity follow-up therefore reaches its lead and company through `opportunities.lead_id`. That's a real foreign key, so no name matching is involved.

- **Conversion** never changes existing follow-ups. A lead's earlier follow-ups stay on the lead.
- **Ownership and RBAC** come from the lead's assigned employee. For an opportunity follow-up, that's its opportunity's lead's assigned employee.

## Why the list showed "Lead: -"

The list, the detail page and the export read only `followups.lead_id`. For opportunity follow-ups, which includes every conversion "next action", that's NULL by design. So a dash appeared even though the company was one hop away.

Every follow-up response now carries a derived, read-only `related` block (`FollowUpRelatedRecord`):
- `kind`
- `leadId`, `companyName`, `contactPerson`
- `leadViaOpportunity`
- `opportunityId`, `opportunityTitle`, `opportunityStage`
- `ownerId`, `ownerName`
- `label`

It's built only from those foreign keys. Nothing is stored or rewritten. The list, detail and export all use it.

## Choosing the record (the "Related record" picker)

`GET /api/followups/targets?search=&type=&limit=` returns records the caller may schedule follow-ups for. It's scoped by `FOLLOWUP_MANAGE`, the same rule the save enforces again.

- **Leads:** unconverted and still prospects (NEW, VALID_UNCONVERTED).
- **Opportunities:** every stage, with the lead's company, contact, stage, owner and id. A **converted lead** is chosen through its opportunity.
- **Not offered:** INVALID leads. Lead Details can still add a follow-up to one directly.

When a save names both a lead and an opportunity that belong to different records, it's refused with `400`.

## Historical data: read-only check (no changes)

Run this once against the database **read-only**, for example in the Supabase SQL editor. It returns counts only, never names:

```sql
SELECT
  count(*)                                                                       AS follow_ups,
  count(*) FILTER (WHERE f.lead_id IS NOT NULL AND f.opportunity_id IS NULL)     AS lead_only,
  count(*) FILTER (WHERE f.lead_id IS NULL AND f.opportunity_id IS NOT NULL)     AS opportunity_only,
  count(*) FILTER (WHERE f.lead_id IS NOT NULL AND f.opportunity_id IS NOT NULL) AS both_set,
  count(*) FILTER (WHERE f.lead_id IS NOT NULL AND f.opportunity_id IS NOT NULL
                     AND o.lead_id IS DISTINCT FROM f.lead_id)                   AS both_set_but_different_records,
  count(*) FILTER (WHERE f.lead_id IS NULL AND f.opportunity_id IS NOT NULL
                     AND o.lead_id IS NULL)                                      AS opportunity_without_lead,
  count(*) FILTER (WHERE f.lead_id IS NULL AND f.opportunity_id IS NULL)         AS no_link_at_all
FROM followups f
LEFT JOIN opportunities o ON o.id = f.opportunity_id;
```

How to read it:

- **`opportunity_only`:** these were the "Lead: -" rows. They're correct as stored and now display their company. **No backfill is needed or wanted.** Copying `opportunities.lead_id` into `followups.lead_id` would change them into "both" rows for no benefit.
- **`opportunity_without_lead`** and **`no_link_at_all`:** expected to be 0, since the application can't create these. Any such row can't be repaired reliably. Its company was never recorded, and guessing from a name could attach it to the wrong customer.
  - The UI shows "No linked lead" or "No linked record" for these rows, and still shows any opportunity.
  - Only an administrator (ALL scope) can open them, because no owner can be resolved.
  - They never cause an error.
- **`both_set_but_different_records`:** expected to be 0. New saves can't create these. If any exist, review them by hand; the display uses the follow-up's own lead.

Deleting a lead or an opportunity is an explicit action that also deletes its follow-ups (`LeadService` / `OpportunityService`). There's no JPA cascade, and conversion never deletes anything.
