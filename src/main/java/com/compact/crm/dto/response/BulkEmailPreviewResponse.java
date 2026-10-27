package com.compact.crm.dto.response;

import com.compact.crm.enums.BulkEmailTarget;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

// Result of the review step: every requested record, whether it can be emailed
// and - if not - exactly why. Contains nothing about records the caller has no
// access to beyond "not available" (their existence and details are not leaked).
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BulkEmailPreviewResponse {

    private BulkEmailTarget target;
    private int requested;
    private int eligibleCount;
    private int excludedCount;
    private List<Recipient> recipients;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Recipient {

        private Long id;
        private String companyName;
        private String contactPerson;
        private String email;
        private boolean eligible;

        // NOT_AVAILABLE | NO_EMAIL | INVALID_EMAIL | DUPLICATE_EMAIL | UNRESOLVED_PLACEHOLDER
        private String exclusionCode;
        private String exclusionMessage;

        // Non-blocking notes, e.g. the lead is marked INVALID, or other selected
        // records share this address.
        private List<String> warnings;

        // This recipient's personalised message (only when subject/body were supplied).
        private String renderedSubject;
        private String renderedBody;
    }
}
