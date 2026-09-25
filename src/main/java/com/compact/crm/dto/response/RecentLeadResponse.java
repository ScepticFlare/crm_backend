package com.compact.crm.dto.response;

import com.compact.crm.enums.LeadStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

// Backs the Dashboard's "Recent Leads" widget - a JPQL constructor
// projection (see repository.LeadRepository.findRecent*) so that widget
// never has to pull a full page of Lead entities (with their industry/
// leadSource/assignedEmployee/products/batteries) just to show 5 rows of
// company name + status.
@Getter
@AllArgsConstructor
public class RecentLeadResponse {

    private Long id;
    private String companyName;
    private String contactPerson;
    private LeadStatus leadStatus;
    private LocalDateTime createdAt;
}
