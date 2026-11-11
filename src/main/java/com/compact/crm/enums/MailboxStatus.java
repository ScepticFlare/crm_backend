package com.compact.crm.enums;

// CONNECTED: a usable (encrypted) refresh token is stored.
// NEEDS_REAUTH: the provider refused the stored credential (consent revoked,
// password reset, token expired, identity changed) - the token has been wiped
// and the employee must connect again. Nothing is ever sent in this state.
public enum MailboxStatus {
    CONNECTED,
    NEEDS_REAUTH
}
