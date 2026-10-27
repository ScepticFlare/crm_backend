package com.compact.crm.enums;

// What a bulk email batch is addressed through. An OPPORTUNITY has no email
// of its own: it is sent to the originating Lead's address, and ownership is
// the Lead's assigned employee (the same rule OpportunitySpecifications.ownerIn
// uses).
public enum BulkEmailTarget {
    LEAD,
    OPPORTUNITY
}
