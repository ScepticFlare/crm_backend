package com.compact.crm.service.mail;

import com.compact.crm.enums.MailboxProvider;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// Every setting of the employee-mailbox email feature, read from
// application.properties (and from there the Render environment variables -
// see docs/email-provider-migration.md). Secrets are only ever held here and
// handed to the provider's token endpoint; they are never logged or returned
// to the frontend.
@Getter
@Component
public class MailboxProperties {

    private final String googleClientId;
    private final String googleClientSecret;
    private final String googleRedirectUri;

    private final String microsoftClientId;
    private final String microsoftClientSecret;
    private final String microsoftTenantId;
    private final String microsoftRedirectUri;

    // Where the browser is sent back to after a connect attempt (the CRM's
    // Email Account page). Only a fixed result code is appended - never a token.
    private final String returnUrl;

    // When true (the default) a mailbox can only be connected if its address is
    // the employee's own CRM login address. This is what stops an employee from
    // getting a colleague to approve a consent screen and then sending as them.
    private final boolean requireEmployeeEmailMatch;

    private final int stateTtlMinutes;

    // Pacing per mailbox (see MailboxRateLimiter) and the daily cap per mailbox
    // (BulkEmailService) - set BELOW the providers' own limits, never above.
    private final int googlePerMinute;
    private final long googleMinIntervalMs;
    private final int googleDailyLimit;
    private final int microsoftPerMinute;
    private final long microsoftMinIntervalMs;
    private final int microsoftDailyLimit;

    // How long one request may wait for the per-minute window before it
    // answers 429 with Retry-After instead (the browser times out at 30 s).
    private final long maxWaitPerRequestMs;

    public MailboxProperties(
            @Value("${crm.mail.google.client-id:}") String googleClientId,
            @Value("${crm.mail.google.client-secret:}") String googleClientSecret,
            @Value("${crm.mail.google.redirect-uri:}") String googleRedirectUri,
            @Value("${crm.mail.microsoft.client-id:}") String microsoftClientId,
            @Value("${crm.mail.microsoft.client-secret:}") String microsoftClientSecret,
            @Value("${crm.mail.microsoft.tenant-id:common}") String microsoftTenantId,
            @Value("${crm.mail.microsoft.redirect-uri:}") String microsoftRedirectUri,
            @Value("${crm.mail.oauth.return-url:http://localhost:5173/email-account}") String returnUrl,
            @Value("${crm.mail.mailbox.require-employee-email-match:true}") boolean requireEmployeeEmailMatch,
            @Value("${crm.mail.oauth.state-ttl-minutes:10}") int stateTtlMinutes,
            @Value("${crm.mail.google.per-minute:60}") int googlePerMinute,
            @Value("${crm.mail.google.min-interval-ms:500}") long googleMinIntervalMs,
            @Value("${crm.mail.google.daily-limit:400}") int googleDailyLimit,
            @Value("${crm.mail.microsoft.per-minute:30}") int microsoftPerMinute,
            @Value("${crm.mail.microsoft.min-interval-ms:0}") long microsoftMinIntervalMs,
            @Value("${crm.mail.microsoft.daily-limit:2000}") int microsoftDailyLimit,
            @Value("${crm.mail.mailbox.max-wait-per-request-ms:12000}") long maxWaitPerRequestMs
    ) {
        this.googleClientId = trim(googleClientId);
        this.googleClientSecret = trim(googleClientSecret);
        this.googleRedirectUri = trim(googleRedirectUri);
        this.microsoftClientId = trim(microsoftClientId);
        this.microsoftClientSecret = trim(microsoftClientSecret);
        this.microsoftTenantId = trim(microsoftTenantId).isEmpty() ? "common" : trim(microsoftTenantId);
        this.microsoftRedirectUri = trim(microsoftRedirectUri);
        this.returnUrl = trim(returnUrl);
        this.requireEmployeeEmailMatch = requireEmployeeEmailMatch;
        this.stateTtlMinutes = Math.max(1, stateTtlMinutes);
        this.googlePerMinute = googlePerMinute;
        this.googleMinIntervalMs = googleMinIntervalMs;
        this.googleDailyLimit = googleDailyLimit;
        this.microsoftPerMinute = microsoftPerMinute;
        this.microsoftMinIntervalMs = microsoftMinIntervalMs;
        this.microsoftDailyLimit = microsoftDailyLimit;
        this.maxWaitPerRequestMs = maxWaitPerRequestMs;
    }

    public boolean isProviderConfigured(MailboxProvider provider) {
        return switch (provider) {
            case GOOGLE -> !googleClientId.isEmpty() && !googleClientSecret.isEmpty() && !googleRedirectUri.isEmpty();
            case MICROSOFT -> !microsoftClientId.isEmpty() && !microsoftClientSecret.isEmpty()
                    && !microsoftRedirectUri.isEmpty();
        };
    }

    public int perMinute(MailboxProvider provider) {
        return provider == MailboxProvider.GOOGLE ? googlePerMinute : microsoftPerMinute;
    }

    public long minIntervalMs(MailboxProvider provider) {
        return provider == MailboxProvider.GOOGLE ? googleMinIntervalMs : microsoftMinIntervalMs;
    }

    public int dailyLimit(MailboxProvider provider) {
        return provider == MailboxProvider.GOOGLE ? googleDailyLimit : microsoftDailyLimit;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
