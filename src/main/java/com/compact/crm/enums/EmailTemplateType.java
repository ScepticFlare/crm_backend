package com.compact.crm.enums;

// The two email flows the Lead email feature supports - see
// entity.EmailTemplate and service.LeadEmailService. Kept as its own enum
// rather than reusing enums.DocumentType, even though the two happen to
// share a "brochure vs everything else" split, because they classify
// different things (a template's purpose vs a file's kind) and are not
// guaranteed to stay in lockstep.
public enum EmailTemplateType {
    PRODUCT_BROCHURE,
    KEEP_IN_TOUCH
}
