package com.compact.crm.dto.response;

import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.Opportunity;

// What a follow-up is about, resolved ONLY through its real foreign keys -
// never by matching names. Serialized on every follow-up as "related", so the
// list, the detail page and the export identify a row the same way.
//
// A follow-up belongs to a Lead (a contact attempt before conversion), to an
// Opportunity (e.g. the next action created when a lead is converted - those
// rows have NO lead_id), or to both. Every Opportunity is created from a Lead
// (OpportunityService.convertLeadToOpportunity), so an opportunity follow-up's
// company and lead are that opportunity's lead: `leadViaOpportunity` = true.
//
//   kind: LEAD | OPPORTUNITY | LEAD_AND_OPPORTUNITY | NONE
public record FollowUpRelatedRecord(
        String kind,
        Long leadId,
        String companyName,
        String contactPerson,
        boolean leadViaOpportunity,
        Long opportunityId,
        String opportunityTitle,
        String opportunityStage,
        Long ownerId,
        String ownerName,
        String label
) {

    public static FollowUpRelatedRecord of(Lead directLead, Opportunity opportunity) {

        // The follow-up's own lead wins; otherwise the opportunity's own lead.
        Lead lead = directLead != null ? directLead : opportunity != null ? opportunity.getLead() : null;
        boolean viaOpportunity = directLead == null && lead != null;

        String kind = directLead != null && opportunity != null ? "LEAD_AND_OPPORTUNITY"
                : directLead != null ? "LEAD"
                : opportunity != null ? "OPPORTUNITY"
                : "NONE";

        String company = lead != null ? blankToNull(lead.getCompanyName()) : null;

        // An opportunity can carry its own contact (V14); fall back to the lead's.
        String contact = opportunity != null && blankToNull(opportunity.getContactPerson()) != null
                ? opportunity.getContactPerson().trim()
                : lead != null ? blankToNull(lead.getContactPerson()) : null;

        String title = opportunity != null ? blankToNull(opportunity.getTitle()) : null;
        String stage = opportunity != null && opportunity.getSalesStage() != null
                ? opportunity.getSalesStage().getName() : null;

        Employee owner = lead != null ? lead.getAssignedEmployee() : null;

        return new FollowUpRelatedRecord(kind,
                lead != null ? lead.getId() : null, company, contact, viaOpportunity,
                opportunity != null ? opportunity.getId() : null, title, stage,
                owner != null ? owner.getId() : null, owner != null ? owner.getName() : null,
                label(company, title, opportunity != null));
    }

    // "Acme Ltd - 6kva online ups", "Acme Ltd", "Opportunity: 6kva online ups"
    // (an opportunity without a lead - none is created by the app, but older
    // data is shown, not hidden), or "No linked record".
    static String label(String company, String title, boolean hasOpportunity) {
        if (company != null && title != null) {
            return company + " - " + title;
        }
        if (company != null) {
            return company;
        }
        if (title != null || hasOpportunity) {
            return "Opportunity: " + (title != null ? title : "(untitled)");
        }
        return "No linked record";
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
