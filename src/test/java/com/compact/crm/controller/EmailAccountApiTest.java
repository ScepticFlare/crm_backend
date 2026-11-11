package com.compact.crm.controller;

import com.compact.crm.entity.EmailMailboxAccount;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.Permission;
import com.compact.crm.entity.Role;
import com.compact.crm.entity.RolePermission;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.MailboxProvider;
import com.compact.crm.enums.MailboxStatus;
import com.compact.crm.enums.Scope;
import com.compact.crm.repository.EmailMailboxAccountRepository;
import com.compact.crm.repository.EmailOAuthStateRepository;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.PermissionRepository;
import com.compact.crm.repository.RolePermissionRepository;
import com.compact.crm.repository.RoleRepository;
import com.compact.crm.security.JwtService;
import com.compact.crm.security.UserPrincipal;
import com.compact.crm.service.mail.TokenCipher;
import com.compact.crm.service.mail.MailboxSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /api/email-accounts over HTTP with the real security chain. The OAuth
 * clients are real but never reach a provider: connect only builds a URL, and
 * every callback here fails state validation before any token exchange.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EmailAccountApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private TokenCipher cipher;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private RolePermissionRepository rolePermissionRepository;
    @Autowired private EmailMailboxAccountRepository accountRepository;
    @Autowired private EmailOAuthStateRepository stateRepository;
    @Autowired private LeadRepository leadRepository;

    private Employee asha;
    private Employee bob;
    private Employee noSend;

    @BeforeEach
    void setUp() {

        Role sales = roleRepository.save(Role.builder().name("MAILBOX_SALES").rank(10).build());
        Role restricted = roleRepository.save(Role.builder().name("MAILBOX_NOSEND").rank(10).build());

        Permission send = permissionRepository.findAll().stream().filter(p -> "EMAIL_SEND".equals(p.getCode())).findFirst()
                .orElseGet(() -> permissionRepository.save(Permission.builder().code("EMAIL_SEND").build()));
        rolePermissionRepository.save(RolePermission.builder().role(sales).permission(send).scope(Scope.OWN).build());

        asha = employeeRepository.save(Employee.builder().name("Asha").email("asha-mb@company.test").phone("9711000001")
                .password("x").role(sales).isActive(true).build());
        bob = employeeRepository.save(Employee.builder().name("Bob").email("bob-mb@company.test").phone("9711000002")
                .password("x").role(sales).isActive(true).build());
        noSend = employeeRepository.save(Employee.builder().name("Nos").email("nos-mb@company.test").phone("9711000003")
                .password("x").role(restricted).isActive(true).build());
    }

    @Test
    void everyEndpointButTheCallback_requiresAuthentication() throws Exception {

        mockMvc.perform(get("/api/email-accounts/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/email-accounts/connect/google")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/email-accounts/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void theCallback_isReachableWithoutAToken_butAForgedStateConnectsNothing() throws Exception {

        mockMvc.perform(get("/api/email-accounts/oauth/google/callback")
                        .param("code", "attacker-code").param("state", "forged"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", startsWith("http://localhost:5173/email-account?")))
                .andExpect(header().string("Location", containsString("result=error")))
                .andExpect(header().string("Location", containsString("reason=invalid_state")))
                .andExpect(header().string("Location", not(containsString("attacker-code"))))
                .andExpect(header().string("Cache-Control", "no-store"));

        assertThat(accountRepository.count()).isZero();
    }

    @Test
    void connect_returnsTheProvidersConsentUrl_andBindsTheStateToTheCaller() throws Exception {

        mockMvc.perform(post("/api/email-accounts/connect/google").header("Authorization", bearer(asha)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizationUrl", startsWith("https://accounts.google.com/o/oauth2/v2/auth?")))
                .andExpect(jsonPath("$.authorizationUrl", containsString("code_challenge_method=S256")))
                .andExpect(jsonPath("$.authorizationUrl", containsString("gmail.send")));

        mockMvc.perform(post("/api/email-accounts/connect/microsoft").header("Authorization", bearer(asha)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizationUrl", startsWith("https://login.microsoftonline.com/common/oauth2/v2.0/authorize?")))
                .andExpect(jsonPath("$.authorizationUrl", containsString("Mail.Send")));

        assertThat(stateRepository.findAll()).hasSize(2).allSatisfy(s -> assertThat(s.getEmployeeId()).isEqualTo(asha.getId()));
    }

    @Test
    void connect_isForbiddenWithoutEmailSend_andUnknownProvidersAreRejected() throws Exception {

        mockMvc.perform(post("/api/email-accounts/connect/google").header("Authorization", bearer(noSend)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/email-accounts/connect/yahoo").header("Authorization", bearer(asha)))
                .andExpect(status().isBadRequest());

        assertThat(stateRepository.count()).isZero();
    }

    @Test
    void status_andDisconnect_onlyEverActOnTheCallersOwnMailbox() throws Exception {

        connected(asha, "asha-mb@company.test");

        mockMvc.perform(get("/api/email-accounts/me").header("Authorization", bearer(asha)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.provider").value("google"))
                .andExpect(jsonPath("$.emailAddress").value("asha-mb@company.test"))
                .andExpect(jsonPath("$.providers.google").value(true))
                .andExpect(content().string(not(containsString("refresh"))))
                .andExpect(content().string(not(containsString("rt-secret"))));

        mockMvc.perform(get("/api/email-accounts/me").header("Authorization", bearer(bob)))
                .andExpect(jsonPath("$.connected").value(false));

        // Bob's disconnect cannot touch Asha's mailbox
        mockMvc.perform(delete("/api/email-accounts/me").header("Authorization", bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(false));
        assertThat(accountRepository.findByEmployeeId(asha.getId())).isPresent();
    }

    @Test
    void bulkEmail_withoutAMailbox_answers409_withAClearMessage() throws Exception {

        Lead lead = leadRepository.save(Lead.builder().companyName("Mail Co").contactPerson("Mo").email("mo@mail.test")
                .phone("9000000000").leadStatus(LeadStatus.VALID).assignedEmployee(bob).build());

        mockMvc.perform(post("/api/bulk-email/batches").header("Authorization", bearer(bob))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"LEAD\",\"ids\":[" + lead.getId() + "],\"subject\":\"S\",\"body\":\"B\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("MAILBOX_NOT_CONNECTED"))
                .andExpect(jsonPath("$.message").value(MailboxSender.CONNECT_MESSAGE));
    }

    @Test
    void aMailboxNeedingReconnection_isReportedAsSuch() throws Exception {

        EmailMailboxAccount a = connected(bob, "bob-mb@company.test");
        a.setStatus(MailboxStatus.NEEDS_REAUTH);
        a.setRefreshTokenEncrypted(null);
        a.setStatusDetail("Google no longer accepts this mailbox connection.");
        accountRepository.save(a);

        mockMvc.perform(get("/api/email-accounts/me").header("Authorization", bearer(bob)))
                .andExpect(jsonPath("$.needsReconnect").value(true))
                .andExpect(jsonPath("$.statusDetail").value("Google no longer accepts this mailbox connection."));

        mockMvc.perform(post("/api/leads/999999/email/send").header("Authorization", bearer(bob))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"KEEP_IN_TOUCH\",\"subject\":\"Hello\",\"body\":\"Hi\"}"))
                .andExpect(status().isNotFound());    // authorization + lookup still come first
    }

    private EmailMailboxAccount connected(Employee e, String address) {
        return accountRepository.save(EmailMailboxAccount.builder()
                .employeeId(e.getId()).provider(MailboxProvider.GOOGLE).emailAddress(address)
                .providerSubject("sub-" + e.getId())
                .refreshTokenEncrypted(cipher.encrypt("rt-secret", MailboxSender.refreshContext(e.getId(), MailboxProvider.GOOGLE)))
                .status(MailboxStatus.CONNECTED).connectedAt(LocalDateTime.now()).build());
    }

    private String bearer(Employee e) {
        return "Bearer " + jwtService.generateToken(new UserPrincipal(e));
    }
}
