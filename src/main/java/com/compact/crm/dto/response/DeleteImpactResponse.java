package com.compact.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Backs the "delete impact" preview endpoints (GET .../{id}/delete-impact
// on Lead/Opportunity/Customer) - tells the frontend exactly what a delete
// of this record will also remove, so the confirmation dialog can be built
// from real counts instead of the frontend (or the Admin) having to guess
// the relational chain. Deliberately just counts + one flag, not a full
// breakdown of the records themselves - the frontend only needs enough to
// render "This Lead has: 1 Opportunity, 1 Customer/Won record, 3
// Follow-Ups", never the records' own data.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DeleteImpactResponse {

    private int opportunityCount;
    private int customerCount;
    private int followUpCount;

    // True when a Won Customer conversion is anywhere in the chain being
    // deleted - lets the frontend show the stronger "this has progressed to
    // a Won Customer" warning called for by the business-safety rule,
    // rather than just a generic dependent-record count.
    private boolean wonCustomer;
}
