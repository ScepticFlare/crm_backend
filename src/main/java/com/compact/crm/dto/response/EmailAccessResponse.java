package com.compact.crm.dto.response;

import com.compact.crm.enums.Scope;

// Whether the caller may use the email features (normal and bulk), and over
// whose records - straight from their role's EMAIL_SEND grant
// (AccessControlService), so the frontend shows the email actions to exactly
// the roles the backend allows instead of a hard-coded role list. Only
// controls visibility: every send is still authorized per record.
public record EmailAccessResponse(boolean canSend, Scope scope) {
}
