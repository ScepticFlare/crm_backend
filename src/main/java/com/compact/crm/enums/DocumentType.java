package com.compact.crm.enums;

// The kind of file a Document record represents - see entity.Document.
// PRODUCT_BROCHURE documents are optionally linked to a Product
// (Document.product) so the Lead email composer can auto-suggest the right
// brochure for a Lead's interested product(s); COMPANY_PROFILE and GENERAL
// are never linked to a Product.
public enum DocumentType {
    PRODUCT_BROCHURE,
    COMPANY_PROFILE,
    GENERAL
}
