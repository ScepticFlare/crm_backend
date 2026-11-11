package com.compact.crm.service.mail;

import com.compact.crm.entity.EmailMailboxAccount;
import com.compact.crm.entity.Employee;
import com.compact.crm.enums.MailboxProvider;
import com.compact.crm.enums.MailboxStatus;
import com.compact.crm.exception.IntegrationNotConfiguredException;
import com.compact.crm.exception.MailboxUnavailableException;
import com.compact.crm.repository.EmailMailboxAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Mailbox resolution, token refresh and dispatch with a FAKE token endpoint and
 * a FAKE provider - nothing reaches Google or Microsoft, no email is sent.
 */
class MailboxSenderTest {

    private EmailMailboxAccountRepository repo;
    private TokenCipher cipher;
    private FakeOAuth oauth;
    private FakeProvider gmail;
    private MailboxSender sender;

    private final Employee asha = Employee.builder().id(7L).name("Asha").email("asha@company.test").build();
    private EmailMailboxAccount account;

    @BeforeEach
    void setUp() {

        repo = mock(EmailMailboxAccountRepository.class);
        cipher = MailTestSupport.cipher();
        oauth = new FakeOAuth();
        gmail = new FakeProvider();

        sender = new MailboxSender(repo, cipher, MailTestSupport.properties(), new MailboxRateLimiter(),
                List.of(gmail), List.of(oauth));

        account = EmailMailboxAccount.builder()
                .id(70L).employeeId(7L).provider(MailboxProvider.GOOGLE)
                .emailAddress("asha@company.test").providerSubject("g-sub-7")
                .refreshTokenEncrypted(cipher.encrypt("rt-7", MailboxSender.refreshContext(7L, MailboxProvider.GOOGLE)))
                .status(MailboxStatus.CONNECTED).connectedAt(LocalDateTime.now()).build();

        when(repo.findByEmployeeId(7L)).thenReturn(Optional.of(account));
        when(repo.findById(70L)).thenReturn(Optional.of(account));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ------------------------------------------------------------ resolution

    @Test
    void noConnectedMailbox_isAClearConflict_neverAFallback() {

        when(repo.findByEmployeeId(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sender.requireReady(asha))
                .isInstanceOfSatisfying(MailboxUnavailableException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("MAILBOX_NOT_CONNECTED");
                    assertThat(e.getMessage()).contains("Connect your Gmail or Microsoft 365 mailbox");
                });
        assertThat(gmail.sent).isEmpty();
    }

    @Test
    void withoutAnEncryptionKey_theFeatureIsNotConfigured() {

        MailboxSender off = new MailboxSender(repo, new TokenCipher("", ""), MailTestSupport.properties(),
                new MailboxRateLimiter(), List.of(gmail), List.of(oauth));

        assertThatThrownBy(() -> off.requireReady(asha)).isInstanceOf(IntegrationNotConfiguredException.class);
    }

    @Test
    void aMailboxNeedingReauth_isRefused_withoutCallingTheProvider() {

        account.setStatus(MailboxStatus.NEEDS_REAUTH);
        account.setRefreshTokenEncrypted(null);

        assertThatThrownBy(() -> sender.requireReady(asha))
                .isInstanceOfSatisfying(MailboxUnavailableException.class,
                        e -> assertThat(e.getCode()).isEqualTo("MAILBOX_RECONNECT_REQUIRED"));
        assertThat(oauth.refreshCalls).isZero();
    }

    // ----------------------------------------------------------------- tokens

    @Test
    void theAccessTokenIsRefreshedOnce_thenReusedUntilItExpires() {

        SenderIdentity id = sender.requireReady(asha);

        sender.trySend(id, "a@x.test", "S", "B", "k1");
        sender.trySend(id, "b@x.test", "S", "B", "k2");

        assertThat(oauth.refreshCalls).isEqualTo(1);
        assertThat(oauth.lastRefreshToken).isEqualTo("rt-7");
        assertThat(gmail.tokens).containsExactly("at-1", "at-1");
    }

    @Test
    void revokedConsent_marksTheMailboxForReconnection_andWipesTheStoredToken() {

        oauth.refreshResults.add(new OAuthException("invalid_grant", "revoked", true, false));

        assertThatThrownBy(() -> sender.requireReady(asha))
                .isInstanceOfSatisfying(MailboxUnavailableException.class,
                        e -> assertThat(e.getCode()).isEqualTo("MAILBOX_RECONNECT_REQUIRED"));

        assertThat(account.getStatus()).isEqualTo(MailboxStatus.NEEDS_REAUTH);
        assertThat(account.getRefreshTokenEncrypted()).isNull();
        assertThat(account.getStatusDetail()).contains("revoked or has expired");
    }

    @Test
    void aTransientTokenEndpointFailure_doesNotDisconnectTheMailbox() {

        oauth.refreshResults.add(new OAuthException("provider_unavailable", "Google sign-in could not be reached.", false, true));

        assertThatThrownBy(() -> sender.requireReady(asha)).isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(MailboxUnavailableException.class);

        assertThat(account.getStatus()).isEqualTo(MailboxStatus.CONNECTED);
        assertThat(account.getRefreshTokenEncrypted()).isNotNull();
    }

    @Test
    void aRefreshThatReturnsADifferentAccount_isNeverUsedToSend() {

        oauth.refreshResults.add(new OAuthTokens("at-x", null, 3600, GoogleOAuthClient.SEND_SCOPE, "id-token"));
        oauth.identity = new MailboxOAuthClient.MailboxIdentity("someone-else", null, null, null);

        assertThatThrownBy(() -> sender.requireReady(asha)).isInstanceOf(MailboxUnavailableException.class);

        assertThat(account.getStatus()).isEqualTo(MailboxStatus.NEEDS_REAUTH);
        assertThat(gmail.sent).isEmpty();
    }

    @Test
    void aRotatedRefreshToken_isStoredEncrypted() {

        oauth.refreshResults.add(new OAuthTokens("at-2", "rt-rotated", 3600, GoogleOAuthClient.SEND_SCOPE, null));

        sender.requireReady(asha);

        assertThat(account.getRefreshTokenEncrypted()).doesNotContain("rt-rotated");
        assertThat(cipher.decrypt(account.getRefreshTokenEncrypted(),
                MailboxSender.refreshContext(7L, MailboxProvider.GOOGLE))).isEqualTo("rt-rotated");
    }

    @Test
    void theSendPermissionBeingRemoved_requiresReconnection() {

        oauth.refreshResults.add(new OAuthTokens("at-2", null, 3600, "openid email", null));

        assertThatThrownBy(() -> sender.requireReady(asha)).isInstanceOf(MailboxUnavailableException.class);
        assertThat(account.getStatus()).isEqualTo(MailboxStatus.NEEDS_REAUTH);
    }

    // ---------------------------------------------------------------- sending

    @Test
    void sendsFromTheConnectedAddress_withTheEmployeesName() {

        SenderIdentity id = sender.requireReady(asha);

        assertThat(sender.trySend(id, "lead@customer.test", "S", "B", "k").result())
                .isEqualTo(EmailSendOutcome.Result.ACCEPTED);

        OutgoingEmail sent = gmail.sent.get(0);
        assertThat(sent.fromAddress()).isEqualTo("asha@company.test");
        assertThat(sent.fromName()).isEqualTo("Asha");
        assertThat(sent.to()).isEqualTo("lead@customer.test");
        assertThat(account.getLastUsedAt()).isNotNull();
    }

    @Test
    void anIdentityForAnotherEmployeesMailbox_sendsNothing() {

        sender.requireReady(asha);

        // a forged / stale identity: same account id, someone else's employee id or address
        SenderIdentity forged = new SenderIdentity(70L, 8L, MailboxProvider.GOOGLE, "asha@company.test", "Bob");
        SenderIdentity otherAddress = new SenderIdentity(70L, 7L, MailboxProvider.GOOGLE, "boss@company.test", "Asha");

        assertThat(sender.trySend(forged, "x@y.test", "S", "B", "k").providerCalled()).isFalse();
        assertThat(sender.trySend(otherAddress, "x@y.test", "S", "B", "k").providerCalled()).isFalse();
        assertThat(gmail.sent).isEmpty();
    }

    @Test
    void a401IsRetriedOnceWithAFreshToken_soAnExpiredTokenDoesNotFailTheSend() {

        SenderIdentity id = sender.requireReady(asha);

        gmail.results.add(new EmailSendOutcome(EmailSendOutcome.Result.FAILED, 401, "Gmail HTTP 401",
                EmailSendOutcome.Signal.AUTH_REJECTED, null, true));
        oauth.refreshResults.add(new OAuthTokens("at-fresh", null, 3600, GoogleOAuthClient.SEND_SCOPE, null));

        assertThat(sender.trySend(id, "x@y.test", "S", "B", "k").result()).isEqualTo(EmailSendOutcome.Result.ACCEPTED);
        assertThat(gmail.tokens).containsExactly("at-1", "at-fresh");
        assertThat(account.getStatus()).isEqualTo(MailboxStatus.CONNECTED);
    }

    @Test
    void a401ThatPersists_marksTheMailboxForReconnection() {

        SenderIdentity id = sender.requireReady(asha);

        EmailSendOutcome rejected = new EmailSendOutcome(EmailSendOutcome.Result.FAILED, 401, "Gmail HTTP 401",
                EmailSendOutcome.Signal.AUTH_REJECTED, null, true);
        gmail.results.add(rejected);
        gmail.results.add(rejected);

        EmailSendOutcome outcome = sender.trySend(id, "x@y.test", "S", "B", "k");

        assertThat(outcome.signal()).isEqualTo(EmailSendOutcome.Signal.AUTH_REJECTED);
        assertThat(gmail.tokens).hasSize(2);   // one retry, never a loop
        assertThat(account.getStatus()).isEqualTo(MailboxStatus.NEEDS_REAUTH);
    }

    @Test
    void ourPacing_holdsSendsBack_withoutCallingTheProvider() {

        MailboxProperties tight = new MailboxProperties("google-client", "google-secret", "https://crm.test/cb",
                "ms", "ms", "common", "https://crm.test/cb2", "https://app/email-account", true, 10,
                2, 0, 400, 30, 0, 2000, 0);

        MailboxSender paced = new MailboxSender(repo, cipher, tight, new MailboxRateLimiter(), List.of(gmail), List.of(oauth));

        SenderIdentity id = paced.requireReady(asha);

        assertThat(paced.trySend(id, "1@y.test", "S", "B", "k1").result()).isEqualTo(EmailSendOutcome.Result.ACCEPTED);
        assertThat(paced.trySend(id, "2@y.test", "S", "B", "k2").result()).isEqualTo(EmailSendOutcome.Result.ACCEPTED);

        EmailSendOutcome third = paced.trySend(id, "3@y.test", "S", "B", "k3");

        assertThat(third.signal()).isEqualTo(EmailSendOutcome.Signal.THROTTLED);
        assertThat(third.providerCalled()).isFalse();
        assertThat(third.retryAfterSeconds()).isBetween(1, 60);
        assertThat(gmail.sent).hasSize(2);
    }

    @Test
    void individualSend_reportsAnUnconfirmedOutcomeAsPossiblySent() {

        gmail.results.add(new EmailSendOutcome(EmailSendOutcome.Result.UNKNOWN, 503, "Gmail HTTP 503"));

        assertThatThrownBy(() -> sender.send(asha, "x@y.test", "S", "B", List.of()))
                .isInstanceOf(EmailOutcomeUnknownException.class)
                .hasMessageContaining("may have been sent");
    }

    // ------------------------------------------------------------------ fakes

    static final class FakeOAuth implements MailboxOAuthClient {

        final Deque<Object> refreshResults = new ArrayDeque<>();
        int refreshCalls;
        String lastRefreshToken;
        MailboxIdentity identity = new MailboxIdentity("g-sub-7", null, null, null);

        @Override public MailboxProvider provider() { return MailboxProvider.GOOGLE; }
        @Override public String authorizationUrl(String s, String c, String n, String h) { return "https://consent"; }
        @Override public OAuthTokens exchangeCode(String code, String verifier) { throw new UnsupportedOperationException(); }

        @Override
        public OAuthTokens refresh(String refreshToken) {
            refreshCalls++;
            lastRefreshToken = refreshToken;
            Object next = refreshResults.poll();
            if (next instanceof OAuthException e) {
                throw e;
            }
            return next != null ? (OAuthTokens) next
                    : new OAuthTokens("at-" + refreshCalls, null, 3600, GoogleOAuthClient.SEND_SCOPE, null);
        }

        @Override public MailboxIdentity identify(OAuthTokens tokens, String nonce) { return identity; }
        @Override public boolean hasSendPermission(String scopes) { return scopes != null && scopes.contains("gmail.send"); }
        @Override public boolean revoke(String refreshToken) { return true; }
    }

    static final class FakeProvider implements EmailProvider {

        final List<OutgoingEmail> sent = new ArrayList<>();
        final List<String> tokens = new ArrayList<>();
        final Deque<EmailSendOutcome> results = new ArrayDeque<>();

        @Override public MailboxProvider provider() { return MailboxProvider.GOOGLE; }

        @Override
        public EmailSendOutcome send(String accessToken, OutgoingEmail email) {
            tokens.add(accessToken);
            EmailSendOutcome r = results.poll();
            if (r == null || r.result() == EmailSendOutcome.Result.ACCEPTED) {
                sent.add(email);
            }
            return r != null ? r : EmailSendOutcome.accepted();
        }
    }
}
