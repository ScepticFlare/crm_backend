package com.compact.crm.service.mail;

import com.compact.crm.entity.EmailMailboxAccount;
import com.compact.crm.entity.Employee;
import com.compact.crm.enums.MailboxProvider;
import com.compact.crm.enums.MailboxStatus;
import com.compact.crm.exception.IntegrationNotConfiguredException;
import com.compact.crm.exception.MailboxUnavailableException;
import com.compact.crm.exception.TooManyRequestsException;
import com.compact.crm.repository.EmailMailboxAccountRepository;
import com.compact.crm.service.EmailAttachment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// The single place every outbound CRM email goes through (it replaced the
// shared Brevo account): resolves the AUTHENTICATED employee's own connected
// mailbox, obtains an access token (refreshing it when needed), paces sends per
// mailbox and dispatches through that mailbox's EmailProvider (Gmail API or
// Microsoft Graph).
//
// Never falls back to another mailbox, another employee's mailbox or a shared
// account: no usable mailbox -> MailboxUnavailableException (409) before
// anything is attempted.
//
// Token handling:
//  - the refresh token is stored encrypted (TokenCipher) and decrypted only to
//    call the provider's token endpoint; access tokens live in memory only;
//  - "invalid_grant" (consent revoked, password reset, token expired) wipes the
//    stored token and marks the mailbox NEEDS_REAUTH - the employee reconnects;
//  - every refresh that returns an ID token is checked to still be the SAME
//    provider account, and to still carry the send permission;
//  - a 401 from the send endpoint is retried exactly once with a fresh token
//    (a 401 means the message was not accepted, so that retry cannot duplicate it).
@Slf4j
@Service
public class MailboxSender {

    public static final String NOT_CONFIGURED_MESSAGE =
            "Email sending is not configured on this server. Please contact an administrator.";

    public static final String CONNECT_MESSAGE =
            "Connect your Gmail or Microsoft 365 mailbox first (Email Account in the menu). "
                    + "CRM email is sent from your own mailbox.";

    // Refresh this long before the access token's stated expiry.
    private static final long EXPIRY_MARGIN_SECONDS = 120;

    private final EmailMailboxAccountRepository accountRepository;
    private final TokenCipher cipher;
    private final MailboxProperties properties;
    private final MailboxRateLimiter rateLimiter;
    private final Map<MailboxProvider, EmailProvider> providers = new EnumMap<>(MailboxProvider.class);
    private final Map<MailboxProvider, MailboxOAuthClient> oauthClients = new EnumMap<>(MailboxProvider.class);

    private final Map<Long, CachedToken> tokens = new ConcurrentHashMap<>();
    private final Map<Long, Object> refreshLocks = new ConcurrentHashMap<>();

    public MailboxSender(EmailMailboxAccountRepository accountRepository, TokenCipher cipher,
                         MailboxProperties properties, MailboxRateLimiter rateLimiter,
                         List<EmailProvider> providers, List<MailboxOAuthClient> oauthClients) {
        this.accountRepository = accountRepository;
        this.cipher = cipher;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        providers.forEach(p -> this.providers.put(p.provider(), p));
        oauthClients.forEach(c -> this.oauthClients.put(c.provider(), c));
    }

    private record CachedToken(String accessToken, Instant expiresAt) {
        @Override
        public String toString() {
            return "CachedToken[expiresAt=" + expiresAt + "]";
        }
    }

    // --------------------------------------------------------------- readiness

    // Called by every send path before it commits to sending (before any batch,
    // send-log claim or activity entry is written). Proves the employee has a
    // connected, currently usable mailbox - including a successful token
    // refresh when the cached token has expired.
    public SenderIdentity requireReady(Employee employee) {

        if (!cipher.isConfigured()) {
            throw new IntegrationNotConfiguredException(NOT_CONFIGURED_MESSAGE);
        }

        EmailMailboxAccount account = accountRepository.findByEmployeeId(employee.getId())
                .orElseThrow(() -> new MailboxUnavailableException(MailboxUnavailableException.NOT_CONNECTED,
                        CONNECT_MESSAGE));

        if (!properties.isProviderConfigured(account.getProvider())) {
            throw new IntegrationNotConfiguredException("Sending through " + account.getProvider().label()
                    + " is not configured on this server. Please contact an administrator.");
        }

        if (account.getStatus() != MailboxStatus.CONNECTED || account.getRefreshTokenEncrypted() == null) {
            throw reconnect(account);
        }

        accessToken(account, false);

        return identity(account, employee);
    }

    private SenderIdentity identity(EmailMailboxAccount account, Employee employee) {
        return new SenderIdentity(account.getId(), account.getEmployeeId(), account.getProvider(),
                account.getEmailAddress(), employee.getName());
    }

    // ------------------------------------------------------------------ sending

    // Bulk email: plain text, no attachments.
    public EmailSendOutcome trySend(SenderIdentity sender, String to, String subject, String body, String sendKey) {
        return trySend(sender, to, subject, body, List.of(), sendKey);
    }

    public EmailSendOutcome trySend(SenderIdentity sender, String to, String subject, String body,
                                    List<EmailAttachment> attachments, String sendKey) {

        EmailMailboxAccount account = accountRepository.findById(sender.accountId()).orElse(null);

        // The mailbox must still be the one requireReady resolved, for the same
        // employee and address - otherwise nothing is sent.
        if (account == null
                || !account.getEmployeeId().equals(sender.employeeId())
                || !account.getEmailAddress().equals(sender.emailAddress())
                || account.getProvider() != sender.provider()) {
            return EmailSendOutcome.notSent(EmailSendOutcome.Signal.AUTH_REJECTED,
                    "The connected mailbox changed while sending - nothing was sent", null);
        }

        if (account.getStatus() != MailboxStatus.CONNECTED || account.getRefreshTokenEncrypted() == null) {
            return EmailSendOutcome.notSent(EmailSendOutcome.Signal.AUTH_REJECTED,
                    "The mailbox needs to be reconnected - nothing was sent", null);
        }

        EmailProvider provider = providers.get(account.getProvider());

        Integer wait = rateLimiter.acquire(account.getId(), properties.perMinute(account.getProvider()),
                properties.minIntervalMs(account.getProvider()), properties.getMaxWaitPerRequestMs());

        if (wait != null) {
            return EmailSendOutcome.notSent(EmailSendOutcome.Signal.THROTTLED,
                    "Paused to stay within " + account.getProvider().label() + "'s sending rate - nothing was sent",
                    wait);
        }

        OutgoingEmail email = new OutgoingEmail(account.getEmailAddress(), sender.displayName(), to, subject, body,
                attachments, sendKey);

        String token;

        try {
            token = accessToken(account, false);
        } catch (MailboxUnavailableException e) {
            return EmailSendOutcome.notSent(EmailSendOutcome.Signal.AUTH_REJECTED, e.getMessage(), null);
        } catch (IllegalStateException e) {
            return EmailSendOutcome.notSent(EmailSendOutcome.Signal.UNAVAILABLE, e.getMessage(), null);
        }

        EmailSendOutcome outcome = provider.send(token, email);

        if (outcome.signal() == EmailSendOutcome.Signal.AUTH_REJECTED && Integer.valueOf(401).equals(outcome.httpStatus())) {

            // Not accepted, so one retry with a fresh token cannot duplicate it.
            tokens.remove(account.getId());

            try {
                token = accessToken(account, true);
            } catch (MailboxUnavailableException e) {
                return outcome;
            } catch (IllegalStateException e) {
                return EmailSendOutcome.notSent(EmailSendOutcome.Signal.UNAVAILABLE, e.getMessage(), null);
            }

            outcome = provider.send(token, email);
        }

        if (outcome.signal() == EmailSendOutcome.Signal.AUTH_REJECTED) {
            markNeedsReauth(account, account.getProvider().label()
                    + " refused the mailbox credential. Please reconnect your mailbox.");
        }

        if (outcome.result() != EmailSendOutcome.Result.FAILED) {
            touch(account);
        }

        return outcome;
    }

    // Individual (non-batch) sends: throws on anything but ACCEPTED, with a
    // user-facing message. UNKNOWN is reported as "may have been sent".
    public void send(Employee employee, String to, String subject, String body,
                     List<EmailAttachment> attachments) {

        SenderIdentity sender = requireReady(employee);

        EmailSendOutcome outcome = trySend(sender, to, subject, body, attachments, "single:" + UUID.randomUUID());

        if (outcome.result() == EmailSendOutcome.Result.ACCEPTED) {
            return;
        }

        throw toException(outcome);
    }

    // The exception a caller should raise for an outcome that stops sending.
    public static RuntimeException toException(EmailSendOutcome outcome) {

        return switch (outcome.signal()) {
            case THROTTLED -> new TooManyRequestsException(
                    (outcome.retryAfterSeconds() == null
                            ? "Your mailbox has reached its sending limit for now. Try again later (limits reset within 24 hours)."
                            : "Your mailbox is sending too fast for the provider's limits. Wait a moment and try again.")
                            + (outcome.detail() == null ? "" : " (" + outcome.detail() + ")"),
                    outcome.retryAfterSeconds());
            case AUTH_REJECTED -> new MailboxUnavailableException(MailboxUnavailableException.RECONNECT_REQUIRED,
                    "Your mailbox connection has expired or was revoked. Reconnect it under Email Account, then try again.");
            case MAILBOX_BLOCKED -> new MailboxUnavailableException(MailboxUnavailableException.BLOCKED,
                    "Your mailbox cannot send email right now"
                            + (outcome.detail() == null ? "." : " (" + outcome.detail() + ")."));
            case UNAVAILABLE -> new IllegalStateException(
                    "The email provider could not be reached to authorize sending. Nothing was sent - please try again.");
            case NONE -> outcome.result() == EmailSendOutcome.Result.UNKNOWN
                    ? new EmailOutcomeUnknownException("The email provider did not confirm the send - it may have been sent. "
                    + "Check your mailbox's Sent folder before trying again.")
                    : new IllegalStateException("The email could not be sent"
                    + (outcome.detail() == null ? "." : ": " + outcome.detail()));
        };
    }

    // ------------------------------------------------------------------- tokens

    // Seeds the cache right after a connection, so the first send needs no refresh.
    void remember(Long accountId, OAuthTokens fresh) {
        tokens.put(accountId, new CachedToken(fresh.accessToken(),
                Instant.now().plusSeconds(Math.max(0, fresh.expiresInSeconds() - EXPIRY_MARGIN_SECONDS))));
    }

    public void forget(Long accountId) {
        tokens.remove(accountId);
        rateLimiter.forget(accountId);
    }

    public static String refreshContext(Long employeeId, MailboxProvider provider) {
        return "refresh:" + employeeId + ":" + provider.name();
    }

    private String accessToken(EmailMailboxAccount account, boolean force) {

        CachedToken cached = tokens.get(account.getId());

        if (!force && cached != null && Instant.now().isBefore(cached.expiresAt())) {
            return cached.accessToken();
        }

        synchronized (refreshLocks.computeIfAbsent(account.getId(), id -> new Object())) {

            cached = tokens.get(account.getId());

            if (!force && cached != null && Instant.now().isBefore(cached.expiresAt())) {
                return cached.accessToken();
            }

            return refresh(account);
        }
    }

    private String refresh(EmailMailboxAccount account) {

        MailboxOAuthClient client = oauthClients.get(account.getProvider());
        String context = refreshContext(account.getEmployeeId(), account.getProvider());

        String refreshToken;

        try {
            refreshToken = cipher.decrypt(account.getRefreshTokenEncrypted(), context);
        } catch (IllegalStateException e) {
            log.warn("Mailbox {}: stored refresh token could not be decrypted", account.getId());
            markNeedsReauth(account, "The stored mailbox credential could not be read. Please reconnect your mailbox.");
            throw reconnect(account);
        }

        OAuthTokens fresh;

        try {

            fresh = client.refresh(refreshToken);

            // Same account as when it was connected - never a silent identity switch.
            if (fresh.idToken() != null) {

                MailboxOAuthClient.MailboxIdentity who = client.identify(fresh, null);

                if (!account.getProviderSubject().equalsIgnoreCase(who.subject())) {
                    log.warn("Mailbox {}: refreshed token belongs to a different account", account.getId());
                    markNeedsReauth(account, "The mailbox credential now belongs to a different account. Please reconnect.");
                    throw reconnect(account);
                }
            }

        } catch (OAuthException e) {

            if (e.isTransientFailure()) {
                throw new IllegalStateException(e.getMessage());
            }

            log.warn("Mailbox {}: token refresh refused ({})", account.getId(), e.getCode());

            if (e.isInvalidGrant() || "identity_invalid".equals(e.getCode())) {
                markNeedsReauth(account, account.getProvider().label()
                        + " no longer accepts this mailbox connection (it was revoked or has expired). Please reconnect.");
                throw reconnect(account);
            }

            throw new IllegalStateException(e.getMessage());
        }

        if (fresh.scope() != null && !client.hasSendPermission(fresh.scope())) {
            markNeedsReauth(account, "Permission to send email was removed. Please reconnect and allow sending.");
            throw reconnect(account);
        }

        // Rotated refresh token (Microsoft rotates on every refresh), or a value
        // still written under the previous encryption key: store the new one.
        boolean rotated = fresh.refreshToken() != null && !fresh.refreshToken().equals(refreshToken);

        if (rotated || cipher.needsReEncryption(account.getRefreshTokenEncrypted())) {
            account.setRefreshTokenEncrypted(cipher.encrypt(rotated ? fresh.refreshToken() : refreshToken, context));
            try {
                accountRepository.save(account);
            } catch (RuntimeException e) {
                log.warn("Mailbox {}: could not store the rotated refresh token ({})",
                        account.getId(), e.getClass().getSimpleName());
            }
        }

        remember(account.getId(), fresh);

        return fresh.accessToken();
    }

    private void markNeedsReauth(EmailMailboxAccount account, String detail) {

        tokens.remove(account.getId());

        try {
            account.setStatus(MailboxStatus.NEEDS_REAUTH);
            account.setRefreshTokenEncrypted(null);
            account.setStatusDetail(OAuthSupport.truncate(detail, 300));
            accountRepository.save(account);
        } catch (RuntimeException e) {
            log.warn("Mailbox {}: could not record NEEDS_REAUTH ({})", account.getId(), e.getClass().getSimpleName());
        }
    }

    private void touch(EmailMailboxAccount account) {
        try {
            account.setLastUsedAt(LocalDateTime.now());
            accountRepository.save(account);
        } catch (RuntimeException e) {
            // informational only
        }
    }

    private static MailboxUnavailableException reconnect(EmailMailboxAccount account) {

        String detail = account.getStatusDetail();

        return new MailboxUnavailableException(MailboxUnavailableException.RECONNECT_REQUIRED,
                "Your " + account.getProvider().label() + " mailbox (" + account.getEmailAddress()
                        + ") needs to be reconnected before you can send email"
                        + (detail == null ? "." : ": " + detail));
    }
}
