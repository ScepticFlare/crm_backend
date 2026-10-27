package com.compact.crm.dto.response;

import com.compact.crm.enums.SuppressionReason;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class EmailSuppressionAuditResponse {

    private Long id;
    private String email;
    private String action;
    private SuppressionReason reason;
    private String note;
    private Long performedByEmployeeId;
    private String performedByName;
    private LocalDateTime performedAt;
}
