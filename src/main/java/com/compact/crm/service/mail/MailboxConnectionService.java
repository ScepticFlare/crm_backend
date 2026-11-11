package com.compact.crm.service.mail;

import com.compact.crm.dto.response.MailboxStatusResponse;
import com.compact.crm.entity.EmailMailboxAccount;
import com.compact.crm.entity.EmailOAuthState;
import com.compact.crm.entity.Employee;
import com.compact.crm.enums.ActivityAction;
import com.compact.crm.enums.ActivityModule;
import com.compact.crm.enums.MailboxProvider;
import com.compact.crm.enums.MailboxStatus;
import com.compact.crm.exception.IntegrationNotConfiguredException;
import com.compact.crm.repository.EmailMailboxAccountRepository;
import com.compact.crm.repository.EmailOAuthStateRepository;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.security.AccessControlService;
import com.compact.crm.security.CurrentUserService;
import com.compact.crm.service.ActivityLogService;
import com.compact.crm.service.EmailAddresses;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.compact.crm.security.AccessControlService.EMAIL_SEND;

// Connecting, checking and disconnecting an employee's OWN mailbox.
//
// Connect (OAuth 2.0 authorization code + PKCE S256 + OpenID nonce):
//  1. POST /api/email-accounts/connect/{provider} (JWT-authenticated): a random
//     256-bit state, a PKCE verifier and a nonce are generated; the state's
//     SHA-256, the employee id, the encrypted verifier and the nonce are stored
//     (single use, short expiry) and the provider's consent URL is returned.
//  2. The browser goes to Google/Microsoft; the employee approves.
//  3. GET /api/email-accounts/oauth/{provider}/callback (no JWT - a browser
//     redirect): the state is looked up and CONSUMED (a replay finds nothing),
//     the code is exchanged with the verifier, the ID token is validated (issuer,
//     audience, expiry, nonce), the send permission and the mailbox address are
//     checked, and the encrypted refresh token is stored for THE EMPLOYEE WHO
//     STARTED THE FLOW (from the state row - never from the callback request).
//  4. The browser is redirected to the CRM's Email Account page with a fixed
//     result code only.
//
// Every operation acts on the caller's own mailbox: there is no endpoint that
// takes another employee's id.
@Slf4j
@Service
public class MailboxConnectionService {

    private final EmailMailboxAccountRepository accountRepository;
    private final EmailOAuthStateRepository stateRepository;
    private final EmployeeRepository employeeRepository;
    private final CurrentUserService currentUserService;
    private final AccessControlService accessControlService;
    private final ActivityLogService activityLogService;
    private final TokenCipher cipher;
    private final MailboxProperties properties;
    private final MailboxSender mailboxSender;
    private final Map<MailboxProvider, MailboxOAuthClient> clients = new EnumMap<>(MailboxProvider.class);

    public MailboxConnectionService(EmailMailboxAccountRepository accountRepository,
                                    EmailOAuthStateRepository stateRepository,
                                    EmployeeRepository employeeRepository,
                                    CurrentUserService currentUserService,
                                    AccessControlService accessControlService,
                                    ActivityLogService activityLogService,
                                    TokenCipher cipher,
                                    MailboxProperties properties,
                                    MailboxSender mailboxSender,
                                    List<MailboxOAuthClient> clients) {
        this.accountRepository = accountRepository;
        this.stateRepository = stateRepository;
        this.employeeRepository = employeeRepository;
        this.currentUserService = currentUserService;
        this.accessControlService = accessControlService;
        this.activityLogService = activityLogService;
        this.cipher = cipher;
        this.properties = properties;
        this.mailboxSender = mailboxSender;
        clients.forEach(c -> this.clients.put(c.provider(), c));
    }

    // ------------------------------------------------------------------- status

    public MailboxStatusResponse status() {

        Employee employee = currentUserService.getCurrentEmployee();

        return status(employee, accountRepository.findByEmployeeId(employee.getId()), null);
    }

    private MailboxStatusResponse status(Employee employee, Optional<EmailMailboxAccount> account, Boolean revoked) {

        Map<String, Boolean> providers = new LinkedHashMap<>();

        for (MailboxProvider p : MailboxProvider.values()) {
            providers.put(p.pathName(), cipher.isConfigured() && properties.isProviderConfigured(p));
        }

        boolean canConnect = accessControlService.hasPermission(employee, EMAIL_SEND);

        if (account.isEmpty()) {
            return new MailboxStatusResponse(cipher.isConfigured(), providers, canConnect, false,
                    null, null, null, false, null, null, null, revoked);
        }

        EmailMailboxAccount a = account.get();
        boolean needsReconnect = a.getStatus() != MailboxStatus.CONNECTED || a.getRefreshTokenEncrypted() == null;

        return new MailboxStatusResponse(cipher.isConfigured(), providers, canConnect, true,
                a.getProvider().pathName(), a.getEmailAddress(), a.getStatus().name(), needsReconnect,
                needsReconnect ? a.getStatusDetail() : null, a.getConnectedAt(), a.getLastUsedAt(), revoked);
    }

    // ------------------------------------------------------------------ connect

    public String startConnection(String providerPath) {

        Employee employee = currentUserService.getCurrentEmployee();
        accessControlService.requirePermission(employee, EMAIL_SEND);

        MailboxProvider provider = MailboxProvider.fromPath(providerPath);

        if (!cipher.isConfigured() || !properties.isProviderConfigured(provider)) {
            throw new IntegrationNotConfiguredException("Connecting a " + provider.label()
                    + " mailbox is not configured on this server. Please contact an administrator.");
        }

        LocalDateTime now = LocalDateTime.now();

        stateRepository.deleteExpired(now);
        stateRepository.deleteForEmployee(employee.getId(), provider);

        String state = OAuthSupport.randomUrlSafe(32);
        String verifier = OAuthSupport.randomUrlSafe(64);
        String nonce = OAuthSupport.randomUrlSafe(16);
        String stateHash = OAuthSupport.sha256Hex(state);

        stateRepository.save(EmailOAuthState.builder()
                .stateHash(stateHash)
                .employeeId(employee.getId())
                .provider(provider)
                .codeVerifierEncrypted(cipher.encrypt(verifier, pkceContext(stateHash)))
                .nonce(nonce)
                .createdAt(now)
                .expiresAt(now.plusMinutes(properties.getStateTtlMinutes()))
                .build());

        return clients.get(provider).authorizationUrl(state, OAuthSupport.s256(verifier), nonce, employee.getEmail());
    }

    // Handles the provider's redirect. Returns the CRM URL to send the browser to.
    // Never throws: every failure becomes a fixed, non-secret reason code.
    public String handleCallback(String providerPath, String code, String state, String error) {

        MailboxProvider provider;

        try {
            provider = MailboxProvider.fromPath(providerPath);
        } catch (IllegalArgumentException e) {
            return redirect(null, "invalid_provider");
        }

        try {
            return completeConnection(provider, code, state, error);
        } catch (RuntimeException e) {
            log.warn("{} mailbox connection failed unexpectedly ({})", provider.label(), e.getClass().getSimpleName());
            return redirect(provider, "server_error");
        }
    }

    private String completeConnection(MailboxProvider provider, String code, String state, String error) {

        if (state == null || state.isBlank() || state.length() > 200) {
            return redirect(provider, "invalid_state");
        }

        Optional<EmailOAuthState> found = stateRepository.findByStateHash(OAuthSupport.sha256Hex(state));

        // Consumed BEFORE anything else: a state can complete at most one connection.
        if (found.isEmpty() || stateRepository.consume(found.get().getId()) != 1) {
            return redirect(provider, "invalid_state");
        }

        EmailOAuthState pending = found.get();

        if (pending.getProvider() != provider) {
            return redirect(provider, "invalid_state");
        }

        if (pending.getExpiresAt().isBefore(LocalDateTime.now())) {
            return redirect(provider, "expired_state");
        }

        if (error != null && !error.isBlank()) {
            return redirect(provider, "access_denied".equals(error) ? "access_denied" : OAuthSupport.safeCode(error));
        }

        if (code == null || code.isBlank() || code.length() > 4096) {
            return redirect(provider, "invalid_request");
        }

        Employee employee = employeeRepository.findById(pending.getEmployeeId()).orElse(null);

        if (employee == null || Boolean.FALSE.equals(employee.getIsActive())
                || !accessControlService.hasPermission(employee, EMAIL_SEND)) {
            return redirect(provider, "not_permitted");
        }

        if (!cipher.isConfigured() || !properties.isProviderConfigured(provider)) {
            return redirect(provider, "not_configured");
        }

        MailboxOAuthClient client = clients.get(provider);

        OAuthTokens tokens;
        MailboxOAuthClient.MailboxIdentity identity;

        try {
            String verifier = cipher.decrypt(pending.getCodeVerifierEncrypted(), pkceContext(pending.getStateHash()));
            tokens = client.exchangeCode(code, verifier);
            identity = client.identify(tokens, pending.getNonce());
        } catch (OAuthException e) {
            log.info("{} mailbox connection refused: {}", provider.label(), e.getCode());
            return redirect(provider, OAuthSupport.safeCode(e.getCode()));
        }

        if (!client.hasSendPermission(tokens.scope())) {
            return redirect(provider, "missing_send_permission");
        }

        if (tokens.refreshToken() == null) {
            return redirect(provider, "no_refresh_token");
        }

        if (properties.isRequireEmployeeEmailMatch()
                && !identity.email().equals(EmailAddresses.normalize(employee.getEmail()))) {
            log.info("{} mailbox connection refused: mailbox address is not the employee's CRM address", provider.label());
            return redirect(provider, "email_mismatch");
        }

        // A mailbox can only ever belong to one employee.
        Optional<EmailMailboxAccount> byEmail = accountRepository.findByEmailAddress(identity.email());
        Optional<EmailMailboxAccount> bySubject = accountRepository.findByProviderAndProviderSubject(provider, identity.subject());

        if (byEmail.filter(a -> !a.getEmployeeId().equals(employee.getId())).isPresent()
                || bySubject.filter(a -> !a.getEmployeeId().equals(employee.getId())).isPresent()) {
            return redirect(provider, "mailbox_in_use");
        }

        Optional<EmailMailboxAccount> existing = accountRepository.findByEmployeeId(employee.getId());

        existing.ifPresent(old -> {
            mailboxSender.forget(old.getId());
            boolean sameAccount = old.getProvider() == provider && old.getProviderSubject().equals(identity.subject());
            if (!sameAccount) {
                revokeQuietly(old);
            }
        });

        EmailMailboxAccount account = existing.orElseGet(EmailMailboxAccount::new);

        account.setEmployeeId(employee.getId());
        account.setProvider(provider);
        account.setEmailAddress(identity.email());
        account.setProviderSubject(identity.subject());
        account.setProviderTenantId(identity.tenantId());
        account.setGrantedScopes(OAuthSupport.truncate(tokens.scope(), 1000));
        account.setRefreshTokenEncrypted(cipher.encrypt(tokens.refreshToken(),
                MailboxSender.refreshContext(employee.getId(), provider)));
        account.setStatus(MailboxStatus.CONNECTED);
        account.setStatusDetail(null);
        account.setConnectedAt(LocalDateTime.now());
        account.setLastUsedAt(null);

        try {
            account = accountRepository.saveAndFlush(account);
        } catch (DataIntegrityViolationException e) {
            return redirect(provider, "mailbox_in_use");
        }

        mailboxSender.remember(account.getId(), tokens);

        activityLogService.log(employee, ActivityModule.EMPLOYEE, ActivityAction.MAILBOX_CONNECTED,
                employee.getId(), employee.getName(),
                "Connected " + provider.label() + " mailbox " + identity.email() + " for sending CRM email");

        return redirect(provider, null);
    }

    // --------------------------------------------------------------- disconnect

    public MailboxStatusResponse disconnect() {

        Employee employee = currentUserService.getCurrentEmployee();

        Optional<EmailMailboxAccount> account = accountRepository.findByEmployeeId(employee.getId());

        if (account.isEmpty()) {
            return status(employee, Optional.empty(), null);
        }

        EmailMailboxAccount a = account.get();

        boolean revoked = revokeQuietly(a);

        accountRepository.delete(a);
        mailboxSender.forget(a.getId());

        activityLogService.log(employee, ActivityModule.EMPLOYEE, ActivityAction.MAILBOX_DISCONNECTED,
                employee.getId(), employee.getName(),
                "Disconnected " + a.getProvider().label() + " mailbox " + a.getEmailAddress());

        return status(employee, Optional.empty(), revoked);
    }

    // Revokes at the provider where supported (Google). The CRM's copy is deleted
    // regardless, so the CRM can never use it again either way.
    private boolean revokeQuietly(EmailMailboxAccount account) {

        if (account.getRefreshTokenEncrypted() == null || !cipher.isConfigured()) {
            return false;
        }

        try {
            String token = cipher.decrypt(account.getRefreshTokenEncrypted(),
                    MailboxSender.refreshContext(account.getEmployeeId(), account.getProvider()));
            return clients.get(account.getProvider()).revoke(token);
        } catch (RuntimeException e) {
            log.info("Mailbox {}: revocation skipped ({})", account.getId(), e.getClass().getSimpleName());
            return false;
        }
    }

    // ----------------------------------------------------------------- helpers

    private static String pkceContext(String stateHash) {
        return "pkce:" + stateHash;
    }

    private String redirect(MailboxProvider provider, String errorCode) {

        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(properties.getReturnUrl())
                .queryParam("result", errorCode == null ? "connected" : "error");

        if (errorCode != null) {
            uri.queryParam("reason", errorCode);
        }

        if (provider != null) {
            uri.queryParam("provider", provider.pathName());
        }

        return uri.encode().build().toUriString();
    }
}
