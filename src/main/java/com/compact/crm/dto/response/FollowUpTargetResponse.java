package com.compact.crm.dto.response;

import com.compact.crm.entity.Lead;
import com.compact.crm.entity.Opportunity;

// One record a follow-up can be scheduled for, as offered by
// GET /api/followups/targets. Carries enough context to tell apart records
// with the same name (company, contact, stage, owner, id). `type` LEAD means
// the follow-up is linked to the lead; OPPORTUNITY to the opportunity (whose
// lead is shown for context).
public record FollowUpTargetResponse(
        String type,
        Long leadId,
        Long opportunityId,
        String companyName,
        String contactPerson,
        String opportunityTitle,
        String stage,
        Long assignedEmployeeId,
        String assignedEmployeeName,
        String label
) {

    public static FollowUpTargetResponse lead(Lead lead) {
        FollowUpRelatedRecord r = FollowUpRelatedRecord.of(lead, null);
        return new FollowUpTargetResponse("LEAD", lead.getId(), null, r.companyName(), r.contactPerson(),
                null, null, r.ownerId(), r.ownerName(), r.label());
    }

    public static FollowUpTargetResponse opportunity(Opportunity opportunity) {
        FollowUpRelatedRecord r = FollowUpRelatedRecord.of(null, opportunity);
        return new FollowUpTargetResponse("OPPORTUNITY", r.leadId(), opportunity.getId(), r.companyName(),
                r.contactPerson(), r.opportunityTitle(), r.opportunityStage(), r.ownerId(), r.ownerName(), r.label());
    }
}
