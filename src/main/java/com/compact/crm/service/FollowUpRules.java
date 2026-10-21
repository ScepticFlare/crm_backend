package com.compact.crm.service;

import com.compact.crm.entity.FollowUp;
import com.compact.crm.enums.FollowUpStatus;

import java.time.LocalDateTime;

// What makes a follow-up count as the "next action" an ACTIVE opportunity must
// have. One definition, used by the save-time validation (OpportunityService),
// the missing-follow-up flag shown in the UI, the dashboard reminder counts
// and the follow-up form's own validation, so they can never disagree.
//
// A follow-up QUALIFIES when it is:
//  - PENDING (not COMPLETED / CANCELLED / MISSED, and not a null status);
//  - scheduled for now or later - an overdue one no longer qualifies;
//  - described: its remarks (the "next action") has at least
//    MIN_NEXT_ACTION_LENGTH non-blank characters.
// It may be attached to the opportunity itself or to its originating lead.
public final class FollowUpRules {

    public static final int MIN_NEXT_ACTION_LENGTH = 3;

    public static final String MISSING_MESSAGE =
            "An active opportunity needs a pending follow-up with a next action and a future date. "
                    + "Enter the next action, activity type and follow-up date, or schedule a follow-up for this "
                    + "opportunity first (with a description of the next action) and then save again.";

    public static final String DESCRIPTION_REQUIRED_MESSAGE =
            "Please describe the next action for this follow-up (at least "
                    + MIN_NEXT_ACTION_LENGTH + " characters).";

    private FollowUpRules() {
    }

    public static boolean hasDescription(String nextAction) {
        return nextAction != null && nextAction.trim().length() >= MIN_NEXT_ACTION_LENGTH;
    }

    public static boolean qualifies(FollowUp followUp, LocalDateTime now) {

        return followUp != null
                && followUp.getStatus() == FollowUpStatus.PENDING
                && followUp.getScheduledDate() != null
                && !followUp.getScheduledDate().isBefore(now)
                && hasDescription(followUp.getRemarks());
    }

    // For follow-up data that has not been saved yet (a request to create one).
    public static boolean qualifies(
            FollowUpStatus status, LocalDateTime scheduledDate, String nextAction, LocalDateTime now) {

        return status == FollowUpStatus.PENDING
                && scheduledDate != null
                && !scheduledDate.isBefore(now)
                && hasDescription(nextAction);
    }
}
