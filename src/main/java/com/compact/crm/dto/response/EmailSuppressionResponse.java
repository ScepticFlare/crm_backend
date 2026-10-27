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
public class EmailSuppressionResponse {

    private Long id;
    private String email;
    private SuppressionReason reason;
    private String source;
    private String note;
    private Long createdByEmployeeId;
    private String createdByName;
    private LocalDateTime createdAt;
}
