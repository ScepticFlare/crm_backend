package com.compact.crm.dto.response;

import com.compact.crm.enums.BulkEmailTarget;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

// Review step of a bulk WhatsApp send: every requested record, whether it can
// be messaged and - for eligible ones, when a template was given - the exact
// text it would receive. Advisory: everything is checked again when sending.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BulkWhatsAppPreviewResponse {

    private BulkEmailTarget target;
    private int requested;
    private int eligibleCount;
    private int excludedCount;
    private Long templateId;
    private Integer templateVersion;
    private boolean providerReady;
    private String providerMessage;
    private List<Recipient> recipients;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Recipient {
        private Long id;
        private String companyName;
        private String contactPerson;
        // country code + last 4 digits only
        private String phoneMasked;
        private boolean eligible;
        private String exclusionCode;
        private String exclusionMessage;
        private List<String> warnings;
        private String renderedText;
    }
}
