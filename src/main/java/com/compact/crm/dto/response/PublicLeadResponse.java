package com.compact.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Deliberately minimal - the public endpoint never returns the full Lead
// entity (which would expose internal fields like assignedEmployee,
// leadStatus, leadSource id, etc. to an unauthenticated caller). Just enough
// for the form to show a confirmation.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PublicLeadResponse {

    private boolean success;
    private String message;
    private Long referenceId;
}
