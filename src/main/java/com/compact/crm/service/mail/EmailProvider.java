package com.compact.crm.service.mail;

import com.compact.crm.enums.MailboxProvider;

// Sends one email through a provider's API on behalf of a signed-in mailbox
// owner. Implementations own every provider-specific concern (endpoint, request
// shape, error classification) and never log the access token, the request
// body or the authorization header. Callers go through MailboxSender, which
// resolves the employee's mailbox, obtains the access token and paces sends.
public interface EmailProvider {

    MailboxProvider provider();

    EmailSendOutcome send(String accessToken, OutgoingEmail email);
}
