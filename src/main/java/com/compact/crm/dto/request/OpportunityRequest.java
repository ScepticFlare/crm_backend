package com.compact.crm.dto.request;

import com.compact.crm.enums.LeadValidity;
import com.compact.crm.enums.OpportunitySubStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OpportunityRequest {

    @NotBlank(message = "Opportunity title is required")
    private String title;

    private Double productValue;

    // Required for conversion and for any active (not WON/LOST/POSTPONED/...)
    // Opportunity - enforced in OpportunityService rather than with
    // @NotNull here, because the same request shape is used to edit
    // historical Opportunities that were saved without a date.
    private LocalDate expectedClosingDate;

    @NotBlank(message = "Sales stage is required")
    private String salesStage;

    @NotNull(message = "Please select a Validity.")
    private LeadValidity leadValidity;

    // Only allowed together with the "IN PROGRESS" stage.
    private OpportunitySubStatus subStatus;

    // Free text. Required when the stage is (becoming) LOST.
    private String lostReason;

    // Optional. Only used with the POSTPONED stage.
    private LocalDate postponedReviewDate;

    // Optional free text. Only used with the POSTPONED stage.
    private String postponedReason;

    // Required when an existing expected closing date of an active
    // opportunity is changed - why it moved. Recorded in the history.
    private String closingDateChangeReason;

    // The next action, supplied together with the save so the follow-up is
    // created in the same transaction. Needed for an ACTIVE opportunity that
    // has no qualifying pending follow-up yet (all three go together); ignored
    // for WON / LOST / POSTPONED.
    private String nextActionDescription;

    private java.time.LocalDateTime nextFollowUpDate;

    private Long nextActionActivityTypeId;
}
