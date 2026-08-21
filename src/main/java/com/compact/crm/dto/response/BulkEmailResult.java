package com.compact.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Map;

// Result of an actual bulk Keep in Touch send - same succeeded/skipped
// shape as dto.response.BulkOperationResult (bulk delete), plus a
// per-skipped-id reason since a skip here can mean either "outside your
// EMAIL_SEND scope" or "no email address on file", and the caller should be
// able to tell the user which.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BulkEmailResult {

    private List<Long> succeededIds;
    private List<Long> skippedIds;
    private Map<Long, String> skipReasons;

}
