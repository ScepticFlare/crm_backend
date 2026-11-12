package com.compact.crm.service.mail;

import com.compact.crm.dto.response.MailboxStatusResponse;
import com.compact.crm.entity.ActivityLog;
import com.compact.crm.entity.EmailMailboxAccount;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Permission;
import com.compact.crm.entity.Role;
import com.compact.crm.entity.RolePermission;
import com.compact.crm.enums.ActivityAction;
import com.compact.crm.enums.MailboxProvider;
import com.compact.crm.enums.MailboxStatus;
import com.compact.crm.enums.Scope;
import com.compact.crm.repository.ActivityLogRepository;
import com.compact.crm.repository.EmailMailboxAccountRepository;
import com.compact.crm.repository.EmailOAuthStateRepository;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.PermissionRepository;
import com.compact.crm.repository.RolePermissionRepository;
import com.compact.crm.repository.RoleRepository;
import com.compact.crm.security.AccessControlService;
import com.compact.crm.security.CurrentUserService;
import com.compact.crm.service.ActivityLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The OAuth connect / callback / disconnect flow on a real (H2) database with a
 * FAKE Google token endpoint: state validation, PKCE, employee-to-mailbox
 * association and isolation. No real provider is contacted.
 */
@DataJpaTest
@Import({AccessControlService.class, CurrentUserService.class, ActivityLogService.class})
class MailboxConnectionServiceTest {

    @Autowired private EmailMailboxAccountRepository accountRepository;
    @Autowired private EmailOAuthStateRepository stateRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private RolePermissionRepository rolePermissionRepository;
    @Autowired private ActivityLogRepository activityLogRepository;
    @Autowired private CurrentUserService currentUserService;
    @Autowired private AccessControlService accessControlService;
    @Autowired private ActivityLogService activityLogService;

    private final TokenCipher cipher = MailTestSupport.cipher();
    private FakeGoogle google;
    private MailboxConnectionService service;

    private Employee asha;     // has EMAIL_SEND
    private Employee bob;      // has EMAIL_SEND
    private Employee viewer;   // no EMAIL_SEND

    @BeforeEach
    void setUp() {
        build(MailTestSupport.properties());

        Role sales = roleRepository.save(Role.builder().name("EMPLOYEE").rank(10).build());
        Role readOnly = roleRepository.save(Role.builder().name("VIEWER").rank(5).build());
        Permission send = permissionRepository.save(Permission.builder().code(AccessControlService.EMAIL_SEND).build());
        rolePermissionRepository.save(RolePermission.builder().role(sales).permission(send).scope(Scope.OWN).build());

        asha = employeeRepository.save(Employee.builder().name("Asha").email("asha@company.test").phone("9700000001")
                .password("x").role(sales).isActive(true).build());
        bob = employeeRepository.save(Employee.builder().name("Bob").email("bob@company.test").phone("9700000002")
                .password("x").role(sales).isActive(true).build());
        viewer = employeeRepository.save(Employee.builder().name("Vee").email("vee@company.test").phone("9700000003")
                .password("x").role(readOnly).isActive(true).build());
    }

    private void build(MailboxProperties properties) {
        google = new FakeGoogle();
        MailboxSender sender = new MailboxSender(accountRepository, cipher, properties, new MailboxRateLimiter(),
                List.of(), List.of(google));
        service = new MailboxConnectionService(accountRepository, stateRepository, employeeRepository,
                currentUserService, accessControlService, activityLogService, cipher, properties, sender, List.of(google));
    }

    // ------------------------------------------------------------------ start

    @Test
    void startingAConnection_storesOnlyAHashOfTheState_andAnEncryptedPkceVerifier() {

        login(asha);

        String url = service.startConnection("google");

        assertThat(google.lastChallenge).isNotBlank();
        assertThat(google.lastLoginHint).isEqualTo("asha@company.test");

        var row = stateRepository.findAll().get(0);
        assertThat(row.getEmployeeId()).isEqualTo(asha.getId());
        assertThat(row.getStateHash()).isEqualTo(OAuthSupport.sha256Hex(google.lastState)).isNotEqualTo(google.lastState);
        assertThat(row.getCodeVerifierEncrypted()).doesNotContain(google.lastChallenge);
        assertThat(row.getExpiresAt()).isAfter(LocalDateTime.now());
        assertThat(url).isEqualTo("https://consent.example/?state=" + google.lastState);
    }

    @Test
    void connecting_requiresTheEmailSendPermission() {

        login(viewer);

        assertThatThrownBy(() -> service.startConnection("google")).isInstanceOf(AccessDeniedException.class);
        assertThat(stateRepository.count()).isZero();
    }

    @Test
    void anUnknownProvider_isRejected() {
        login(asha);
        assertThatThrownBy(() -> service.startConnection("yahoo")).isInstanceOf(IllegalArgumentException.class);
    }

    // --------------------------------------------------------------- callback

    @Test
    void aSuccessfulCallback_connectsTheMailbox_toTheEmployeeWhoStartedIt_withTheTokenEncrypted() {

        login(asha);
        service.startConnection("google");
        String state = google.lastState;

        // the callback is an unauthenticated browser redirect - whoever (if anyone)
        // is "logged in" on this thread is irrelevant; the state decides
        login(bob);

        String redirect = service.handleCallback("google", "auth-code", state, null);

        assertThat(params(redirect)).containsEntry("result", "connected").containsEntry("provider", "google");
        assertThat(redirect).doesNotContain("auth-code").doesNotContain("rt-google");

        EmailMailboxAccount saved = accountRepository.findByEmployeeId(asha.getId()).orElseThrow();
        assertThat(saved.getEmailAddress()).isEqualTo("asha@company.test");
        assertThat(saved.getProviderSubject()).isEqualTo("g-asha");
        assertThat(saved.getStatus()).isEqualTo(MailboxStatus.CONNECTED);
        assertThat(saved.getRefreshTokenEncrypted()).doesNotContain("rt-google");
        assertThat(cipher.decrypt(saved.getRefreshTokenEncrypted(),
                MailboxSender.refreshContext(asha.getId(), MailboxProvider.GOOGLE))).isEqualTo("rt-google");
        assertThat(accountRepository.findByEmployeeId(bob.getId())).isEmpty();

        // the PKCE verifier sent at the exchange is the one whose challenge went to the consent URL
        assertThat(OAuthSupport.s256(google.lastVerifier)).isEqualTo(google.lastChallenge);
        // the nonce sent at the start is the one the ID token is checked against
        assertThat(google.lastIdentifyNonce).isEqualTo(google.lastNonce);

        assertThat(activityLogRepository.findAll()).extracting(ActivityLog::getAction)
                .contains(ActivityAction.MAILBOX_CONNECTED);
    }

    @Test
    void aStateCanBeUsedOnlyOnce() {

        login(asha);
        service.startConnection("google");
        String state = google.lastState;

        assertThat(params(service.handleCallback("google", "code", state, null))).containsEntry("result", "connected");
        assertThat(params(service.handleCallback("google", "code", state, null))).containsEntry("reason", "invalid_state");
        assertThat(google.exchanges).isEqualTo(1);
    }

    @Test
    void anUnknownMissingOrForgedState_isRejected_beforeAnyTokenExchange() {

        assertThat(params(service.handleCallback("google", "code", "forged-state", null)))
                .containsEntry("result", "error").containsEntry("reason", "invalid_state");
        assertThat(params(service.handleCallback("google", "code", null, null))).containsEntry("reason", "invalid_state");
        assertThat(google.exchanges).isZero();
        assertThat(accountRepository.count()).isZero();
    }

    @Test
    void aStateIssuedForOneProvider_cannotCompleteAnother() {

        login(asha);
        service.startConnection("google");

        assertThat(params(service.handleCallback("microsoft", "code", google.lastState, null)))
                .containsEntry("reason", "invalid_state");
        assertThat(google.exchanges).isZero();
    }

    @Test
    void anExpiredState_isRejected() {

        login(asha);
        service.startConnection("google");

        var row = stateRepository.findAll().get(0);
        row.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        stateRepository.save(row);

        assertThat(params(service.handleCallback("google", "code", google.lastState, null)))
                .containsEntry("reason", "expired_state");
        assertThat(google.exchanges).isZero();
    }

    @Test
    void declinedConsent_isReported_andConsumesTheState() {

        login(asha);
        service.startConnection("google");

        assertThat(params(service.handleCallback("google", null, google.lastState, "access_denied")))
                .containsEntry("reason", "access_denied");
        assertThat(stateRepository.count()).isZero();
        assertThat(accountRepository.count()).isZero();
    }

    @Test
    void aFailedCodeExchange_orBadIdToken_connectsNothing() {

        login(asha);
        service.startConnection("google");
        google.exchangeError = new OAuthException("invalid_grant", "bad code", true, false);

        assertThat(params(service.handleCallback("google", "code", google.lastState, null)))
                .containsEntry("reason", "invalid_grant");

        service.startConnection("google");
        google.exchangeError = null;
        google.identifyError = OAuthException.identity("nonce mismatch");

        assertThat(params(service.handleCallback("google", "code", google.lastState, null)))
                .containsEntry("reason", "identity_invalid");

        assertThat(accountRepository.count()).isZero();
    }

    @Test
    void consentWithoutTheSendPermission_orWithoutARefreshToken_connectsNothing() {

        login(asha);

        service.startConnection("google");
        google.scope = "openid email";
        assertThat(params(service.handleCallback("google", "code", google.lastState, null)))
                .containsEntry("reason", "missing_send_permission");

        service.startConnection("google");
        google.scope = GoogleOAuthClient.SEND_SCOPE;
        google.refreshToken = null;
        assertThat(params(service.handleCallback("google", "code", google.lastState, null)))
                .containsEntry("reason", "no_refresh_token");

        assertThat(accountRepository.count()).isZero();
    }

    @Test
    void aColleaguesMailbox_cannotBeConnectedToMyAccount() {

        // Asha starts the flow, but Bob is the one who approves the consent screen
        login(asha);
        service.startConnection("google");
        google.email = "bob@company.test";
        google.subject = "g-bob";

        assertThat(params(service.handleCallback("google", "code", google.lastState, null)))
                .containsEntry("reason", "email_mismatch");
        assertThat(accountRepository.count()).isZero();
    }

    @Test
    void aMailboxAlreadyConnectedByAnotherEmployee_cannotBeTakenOver_evenWithoutTheEmailMatchRule() {

        build(MailTestSupport.properties("common", false));

        login(bob);
        service.startConnection("google");
        google.email = "shared@company.test";
        google.subject = "g-shared";
        assertThat(params(service.handleCallback("google", "code", google.lastState, null))).containsEntry("result", "connected");

        login(asha);
        service.startConnection("google");
        assertThat(params(service.handleCallback("google", "code", google.lastState, null)))
                .containsEntry("reason", "mailbox_in_use");

        assertThat(accountRepository.findByEmployeeId(asha.getId())).isEmpty();
        assertThat(accountRepository.findByEmployeeId(bob.getId()).orElseThrow().getEmailAddress())
                .isEqualTo("shared@company.test");
    }

    @Test
    void reconnecting_replacesTheEmployeesSingleMailboxRow() {

        login(asha);
        service.startConnection("google");
        service.handleCallback("google", "code", google.lastState, null);

        EmailMailboxAccount first = accountRepository.findByEmployeeId(asha.getId()).orElseThrow();
        first.setStatus(MailboxStatus.NEEDS_REAUTH);
        first.setRefreshTokenEncrypted(null);
        accountRepository.save(first);

        service.startConnection("google");
        google.refreshToken = "rt-google-2";
        service.handleCallback("google", "code", google.lastState, null);

        assertThat(accountRepository.count()).isEqualTo(1);
        EmailMailboxAccount again = accountRepository.findByEmployeeId(asha.getId()).orElseThrow();
        assertThat(again.getStatus()).isEqualTo(MailboxStatus.CONNECTED);
        assertThat(cipher.decrypt(again.getRefreshTokenEncrypted(),
                MailboxSender.refreshContext(asha.getId(), MailboxProvider.GOOGLE))).isEqualTo("rt-google-2");
    }

    @Test
    void aDeactivatedEmployee_cannotCompleteAConnection() {

        login(asha);
        service.startConnection("google");
        asha.setIsActive(false);
        employeeRepository.save(asha);

        assertThat(params(service.handleCallback("google", "code", google.lastState, null)))
                .containsEntry("reason", "not_permitted");
        assertThat(google.exchanges).isZero();
    }

    // ------------------------------------------------------- status/disconnect

    @Test
    void status_showsOnlyMyOwnMailbox_andNeverAToken() {

        login(asha);
        service.startConnection("google");
        service.handleCallback("google", "code", google.lastState, null);

        MailboxStatusResponse mine = service.status();
        assertThat(mine.connected()).isTrue();
        assertThat(mine.emailAddress()).isEqualTo("asha@company.test");
        assertThat(mine.provider()).isEqualTo("google");
        assertThat(mine.toString()).doesNotContain("rt-google");

        login(bob);
        assertThat(service.status().connected()).isFalse();
    }

    @Test
    void disconnect_revokesAtTheProvider_deletesTheCredential_andOnlyEverTouchesMyOwnMailbox() {

        login(asha);
        service.startConnection("google");
        service.handleCallback("google", "code", google.lastState, null);

        login(bob);
        service.startConnection("google");
        google.email = "bob@company.test";
        google.subject = "g-bob";
        google.refreshToken = "rt-bob";
        service.handleCallback("google", "code", google.lastState, null);

        // Bob disconnects: only Bob's mailbox goes
        MailboxStatusResponse after = service.disconnect();

        assertThat(after.connected()).isFalse();
        assertThat(after.providerRevoked()).isTrue();
        assertThat(google.revoked).containsExactly("rt-bob");
        assertThat(accountRepository.findByEmployeeId(bob.getId())).isEmpty();
        assertThat(accountRepository.findByEmployeeId(asha.getId())).isPresent();
        assertThat(activityLogRepository.findAll()).extracting(ActivityLog::getAction)
                .contains(ActivityAction.MAILBOX_DISCONNECTED);
    }

    // Arvind: some employees are @compactups.in, others @compactups.com, in one
    // Microsoft 365 tenant. Both connect through the same Microsoft flow; the
    // mailbox must still be exactly the employee's own CRM address.
    @Test
    void microsoftMailboxes_onEitherCompanyDomain_connect_butOnlyToTheEmployeeWithThatExactAddress() {

        Role sales = asha.getRole();
        Employee priya = employeeRepository.save(Employee.builder().name("Priya").email("priya@compactups.in")
                .phone("9700000011").password("x").role(sales).isActive(true).build());
        Employee rahul = employeeRepository.save(Employee.builder().name("Rahul").email("rahul@compactups.com")
                .phone("9700000012").password("x").role(sales).isActive(true).build());
        Employee karan = employeeRepository.save(Employee.builder().name("Karan").email("karan@compactups.in")
                .phone("9700000013").password("x").role(sales).isActive(true).build());

        FakeGoogle microsoft = new FakeGoogle();
        microsoft.provider = MailboxProvider.MICROSOFT;
        MailboxProperties properties = MailTestSupport.properties();
        MailboxSender sender = new MailboxSender(accountRepository, cipher, properties, new MailboxRateLimiter(),
                List.of(), List.of(microsoft));
        service = new MailboxConnectionService(accountRepository, stateRepository, employeeRepository,
                currentUserService, accessControlService, activityLogService, cipher, properties, sender, List.of(microsoft));

        // .in employee, .in mailbox
        login(priya);
        service.startConnection("microsoft");
        assertThat(microsoft.lastLoginHint).isEqualTo("priya@compactups.in");
        microsoft.email = "priya@compactups.in";
        microsoft.subject = "oid-priya";
        assertThat(params(service.handleCallback("microsoft", "code", microsoft.lastState, null)))
                .containsEntry("result", "connected").containsEntry("provider", "microsoft");

        // .com employee, .com mailbox
        login(rahul);
        service.startConnection("microsoft");
        microsoft.email = "rahul@compactups.com";
        microsoft.subject = "oid-rahul";
        assertThat(params(service.handleCallback("microsoft", "code", microsoft.lastState, null)))
                .containsEntry("result", "connected");

        // a same-name mailbox on the OTHER domain is a different mailbox: refused
        login(karan);
        service.startConnection("microsoft");
        microsoft.email = "karan@compactups.com";
        microsoft.subject = "oid-karan-com";
        assertThat(params(service.handleCallback("microsoft", "code", microsoft.lastState, null)))
                .containsEntry("reason", "email_mismatch");

        assertThat(accountRepository.findByEmployeeId(priya.getId()).orElseThrow())
                .satisfies(a -> {
                    assertThat(a.getProvider()).isEqualTo(MailboxProvider.MICROSOFT);
                    assertThat(a.getEmailAddress()).isEqualTo("priya@compactups.in");
                });
        assertThat(accountRepository.findByEmployeeId(rahul.getId()).orElseThrow().getEmailAddress())
                .isEqualTo("rahul@compactups.com");
        assertThat(accountRepository.findByEmployeeId(karan.getId())).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private void login(Employee e) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(e.getEmail(), null, List.of()));
    }

    private static Map<String, String> params(String url) {
        return UriComponentsBuilder.fromUriString(url).build().getQueryParams().toSingleValueMap();
    }

    static final class FakeGoogle implements MailboxOAuthClient {

        String lastState, lastChallenge, lastNonce, lastLoginHint, lastVerifier, lastIdentifyNonce;
        String email = "asha@company.test";
        String subject = "g-asha";
        String scope = "openid " + GoogleOAuthClient.SEND_SCOPE;
        String refreshToken = "rt-google";
        OAuthException exchangeError, identifyError;
        int exchanges;
        final List<String> revoked = new ArrayList<>();

        MailboxProvider provider = MailboxProvider.GOOGLE;

        @Override public MailboxProvider provider() { return provider; }

        @Override
        public String authorizationUrl(String state, String challenge, String nonce, String hint) {
            lastState = state; lastChallenge = challenge; lastNonce = nonce; lastLoginHint = hint;
            return "https://consent.example/?state=" + state;
        }

        @Override
        public OAuthTokens exchangeCode(String code, String verifier) {
            exchanges++;
            lastVerifier = verifier;
            if (exchangeError != null) {
                throw exchangeError;
            }
            return new OAuthTokens("at-google", refreshToken, 3600, scope, "id");
        }

        @Override public OAuthTokens refresh(String rt) { throw new UnsupportedOperationException(); }

        @Override
        public MailboxIdentity identify(OAuthTokens tokens, String nonce) {
            lastIdentifyNonce = nonce;
            if (identifyError != null) {
                throw identifyError;
            }
            return new MailboxIdentity(subject, email, null, null);
        }

        @Override public boolean hasSendPermission(String s) { return s != null && s.contains(GoogleOAuthClient.SEND_SCOPE); }

        @Override
        public boolean revoke(String rt) {
            revoked.add(rt);
            return true;
        }
    }
}
