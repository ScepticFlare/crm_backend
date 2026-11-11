package com.compact.crm.service.mail;

import com.compact.crm.enums.MailboxProvider;

// The connected mailbox an employee's email is sent from, as resolved (and
// checked usable) by MailboxSender.requireReady. Carries no credential.
public record SenderIdentity(
        Long accountId,
        Long employeeId,
        MailboxProvider provider,
        String emailAddress,
        String displayName
) {
}
