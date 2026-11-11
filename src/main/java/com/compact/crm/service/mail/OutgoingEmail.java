package com.compact.crm.service.mail;

import com.compact.crm.service.EmailAttachment;

import java.util.List;

// One plain-text email, ready for a provider. fromAddress is always the
// employee's connected mailbox (MailboxSender sets it, never the caller), and
// sendKey identifies this logical send (batch + record, or a random id for a
// single send): it becomes the Message-ID / an X-CRM-Send-Id header so a
// message can be traced in the mailbox's Sent folder.
public record OutgoingEmail(
        String fromAddress,
        String fromName,
        String to,
        String subject,
        String body,
        List<EmailAttachment> attachments,
        String sendKey
) {

    public OutgoingEmail {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }
}
