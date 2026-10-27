package com.compact.crm.controller;

import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.Permission;
import com.compact.crm.entity.Role;
import com.compact.crm.entity.RolePermission;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.Scope;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.PermissionRepository;
import com.compact.crm.repository.RolePermissionRepository;
import com.compact.crm.repository.RoleRepository;
import com.compact.crm.security.JwtService;
import com.compact.crm.security.UserPrincipal;
import com.compact.crm.service.BrevoEmailClient;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-level checks (real security chain, JSON binding, validation, error
 * mapping) of /api/bulk-email and /api/email-suppressions, with a FAKE email
 * transport.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BulkEmailApiTest {

    @MockitoBean private BrevoEmailClient brevo;

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private RolePermissionRepository rolePermissionRepository;
    @Autowired private LeadRepository leadRepository;

    private Employee owner;
    private Employee other;
    private Employee noEmail;
    private Employee admin;
    private Lead mine;
    private Lead theirs;

    @BeforeEach
    void setUp() {

        Role employeeRole = role("EMPLOYEE", 10);
        Role restricted = role("NOEMAIL", 10);
        Role adminRole = role("ADMIN", 100);

        grant(employeeRole, "EMAIL_SEND", Scope.OWN);
        grant(adminRole, "EMAIL_SEND", Scope.ALL);
        grant(adminRole, "EMAIL_SUPPRESSION_MANAGE", Scope.ALL);      // as seeded by V13: ADMIN only

        // a role that was never granted EMAIL_SEND
        grant(restricted, "LEAD_VIEW", Scope.OWN);

        owner = employee("Bulk Owner", "bulk-owner@test.com", "9700000001", employeeRole);
        other = employee("Bulk Other", "bulk-other@test.com", "9700000002", employeeRole);
        noEmail = employee("Bulk NoEmail", "bulk-none@test.com", "9700000003", restricted);
        admin = employee("Bulk Admin", "bulk-admin@test.com", "9700000004", adminRole);

        mine = lead("Mine Co", "Mina", "mina@mine.test", owner);
        theirs = lead("Theirs Co", "Theo", "theo@theirs.test", other);

        when(brevo.trySend(any(), any(), any(), any()))
                .thenReturn(new BrevoEmailClient.SendOutcome(BrevoEmailClient.Result.ACCEPTED, null, null));
    }

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {

        for (String path : new String[]{"/api/bulk-email/preview", "/api/bulk-email/batches", "/api/bulk-email/send"}) {
            mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"target\":\"LEAD\",\"ids\":[1]}"))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(get("/api/bulk-email/batches/abcdefgh-1234")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/email-suppressions")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/email-suppressions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"a@b.test\",\"reason\":\"OPT_OUT\"}")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/email-suppressions/1").param("justification", "because I say so"))
                .andExpect(status().isUnauthorized());

        verify(brevo, never()).trySend(any(), any(), any(), any());
    }

    @Test
    void roleWithoutEmailSend_isForbidden() throws Exception {

        mockMvc.perform(post("/api/bulk-email/preview").header("Authorization", bearer(noEmail))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"LEAD\",\"ids\":[" + mine.getId() + "]}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/bulk-email/batches").header("Authorization", bearer(noEmail))
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(mine.getId())))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/bulk-email/send").header("Authorization", bearer(noEmail))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody(mine.getId(), "00000000-0000-0000-0000-000000000000")))
                .andExpect(status().isForbidden());

        verify(brevo, never()).trySend(any(), any(), any(), any());
    }

    @Test
    void emptyAndMalformedPayloadsAreRejectedWith400() throws Exception {

        String auth = bearer(owner);

        mockMvc.perform(post("/api/bulk-email/preview").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"target\":\"LEAD\",\"ids\":[]}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/bulk-email/preview").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[1]}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/bulk-email/preview").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"target\":\"CUSTOMER\",\"ids\":[1]}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/bulk-email/batches").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"LEAD\",\"ids\":[" + mine.getId() + "]}"))      // no subject/body
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/bulk-email/send").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody(mine.getId(), "short")))
                .andExpect(status().isBadRequest());

        // a well-formed id that does not exist
        mockMvc.perform(post("/api/bulk-email/send").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody(mine.getId(), "00000000-0000-0000-0000-000000000000")))
                .andExpect(status().isNotFound());

        verify(brevo, never()).trySend(any(), any(), any(), any());
    }

    @Test
    void createSendAndRecoverABatch_overHttp() throws Exception {

        String auth = bearer(owner);

        mockMvc.perform(post("/api/bulk-email/preview").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"LEAD\",\"ids\":[" + mine.getId() + "," + theirs.getId()
                                + "],\"subject\":\"Hi {{contactPerson}}\",\"body\":\"Dear {{contactPerson}}\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligibleCount").value(1))
                .andExpect(jsonPath("$.excludedCount").value(1))
                .andExpect(jsonPath("$.recipients[?(@.id==" + mine.getId() + ")].renderedBody").value("Dear Mina"))
                .andExpect(jsonPath("$.recipients[?(@.id==" + theirs.getId() + ")].exclusionCode").value("NOT_AVAILABLE"));

        // the server issues the batch id
        String created = mockMvc.perform(post("/api/bulk-email/batches").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"LEAD\",\"ids\":[" + mine.getId() + "," + theirs.getId()
                                + "],\"subject\":\"Hi\",\"body\":\"Hello {{contactPerson}}\",\"batchId\":\"client-chosen-id-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(2))
                .andExpect(jsonPath("$.chunkSize").value(10))
                .andExpect(jsonPath("$.maxRecipients").value(100))
                .andReturn().getResponse().getContentAsString();

        String batchId = JsonPath.read(created, "$.batchId");
        org.assertj.core.api.Assertions.assertThat(batchId).isNotEqualTo("client-chosen-id-1").hasSize(36);

        // a chunk that adds a record, or changes the message, is rejected
        mockMvc.perform(post("/api/bulk-email/send").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchId\":\"" + batchId + "\",\"ids\":[" + mine.getId() + ",999999]}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/bulk-email/send").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchId\":\"" + batchId + "\",\"ids\":[" + mine.getId()
                                + "],\"subject\":\"Different\",\"body\":\"Hello {{contactPerson}}\"}"))
                .andExpect(status().isBadRequest());

        verify(brevo, never()).trySend(any(), any(), any(), any());

        mockMvc.perform(post("/api/bulk-email/send").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchId\":\"" + batchId + "\",\"ids\":[" + mine.getId() + "," + theirs.getId() + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.excluded").value(1))
                .andExpect(jsonPath("$.results[?(@.id==" + mine.getId() + ")].outcome").value("ACCEPTED"))
                .andExpect(jsonPath("$.results[?(@.id==" + theirs.getId() + ")].exclusionCode").value("NOT_AVAILABLE"));

        // reload-safe: the whole batch, with the message and recipients needed to resume
        mockMvc.perform(get("/api/bulk-email/batches/" + batchId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.excluded").value(1))
                .andExpect(jsonPath("$.results.length()").value(2))
                .andExpect(jsonPath("$.batch.subject").value("Hi"))
                .andExpect(jsonPath("$.batch.recipientIds.length()").value(2));

        // a repeat of the same chunk does not resend
        mockMvc.perform(post("/api/bulk-email/send").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchId\":\"" + batchId + "\",\"ids\":[" + mine.getId() + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].alreadyProcessed").value(true));

        verify(brevo, org.mockito.Mockito.times(1)).trySend(any(), any(), any(), any());

        // another user cannot read or use this batch
        mockMvc.perform(get("/api/bulk-email/batches/" + batchId).header("Authorization", bearer(other)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/bulk-email/send").header("Authorization", bearer(other))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchId\":\"" + batchId + "\",\"ids\":[" + theirs.getId() + "]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void suppressionManagementIsAdminOnly_andEnforcedByTheBulkEndpoints() throws Exception {

        String adminAuth = bearer(admin);

        // an ordinary employee gets 403 on every management endpoint
        mockMvc.perform(get("/api/email-suppressions").header("Authorization", bearer(owner))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/email-suppressions/audit").header("Authorization", bearer(owner))).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/email-suppressions").header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"mina@mine.test\",\"reason\":\"OPT_OUT\"}"))
                .andExpect(status().isForbidden());

        // the admin adds one (case/spacing normalized), duplicates and bad input are 400
        String added = mockMvc.perform(post("/api/email-suppressions").header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\" MINA@Mine.test \",\"reason\":\"OPT_OUT\",\"note\":\"asked to stop\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("mina@mine.test"))
                .andExpect(jsonPath("$.source").value("ADMIN_CONSOLE"))
                .andReturn().getResponse().getContentAsString();

        int id = JsonPath.read(added, "$.id");

        mockMvc.perform(post("/api/email-suppressions").header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"mina@mine.test\",\"reason\":\"MANUAL\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/email-suppressions").header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"x@y.test\",\"reason\":\"WHATEVER\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/email-suppressions").header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"nope\",\"reason\":\"MANUAL\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/email-suppressions").header("Authorization", adminAuth).param("q", "MINE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));

        // the owner's own lead is now blocked in the review and the send
        String ownerAuth = bearer(owner);

        mockMvc.perform(post("/api/bulk-email/preview").header("Authorization", ownerAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"LEAD\",\"ids\":[" + mine.getId() + "],\"subject\":\"S\",\"body\":\"B\"}"))
                .andExpect(jsonPath("$.recipients[0].exclusionCode").value("SUPPRESSED"));

        String batch = JsonPath.read(mockMvc.perform(post("/api/bulk-email/batches").header("Authorization", ownerAuth)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(mine.getId())))
                .andReturn().getResponse().getContentAsString(), "$.batchId");

        mockMvc.perform(post("/api/bulk-email/send").header("Authorization", ownerAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchId\":\"" + batch + "\",\"ids\":[" + mine.getId() + "]}"))
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.results[0].exclusionCode").value("SUPPRESSED"));

        verify(brevo, never()).trySend(any(), any(), any(), any());

        // removal: an employee cannot; the admin needs a justification; both are audited
        mockMvc.perform(delete("/api/email-suppressions/" + id).header("Authorization", ownerAuth)
                        .param("justification", "please remove it"))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/email-suppressions/" + id).header("Authorization", adminAuth))
                .andExpect(status().isBadRequest());                       // justification parameter missing

        mockMvc.perform(delete("/api/email-suppressions/" + id).header("Authorization", adminAuth).param("justification", "no"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(delete("/api/email-suppressions/" + id).header("Authorization", adminAuth)
                        .param("justification", "Customer re-subscribed, ticket 4711"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/email-suppressions/audit").header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].action").value("REMOVED"))
                .andExpect(jsonPath("$.items[0].note").value("Customer re-subscribed, ticket 4711"))
                .andExpect(jsonPath("$.items[0].performedByName").value("Bulk Admin"))
                .andExpect(jsonPath("$.items[1].action").value("ADDED"));

        mockMvc.perform(delete("/api/email-suppressions/" + id).header("Authorization", adminAuth)
                        .param("justification", "removing it a second time"))
                .andExpect(status().isNotFound());
    }

    // ---------- helpers

    private String createBody(Long id) {
        return "{\"target\":\"LEAD\",\"ids\":[" + id + "],\"subject\":\"S\",\"body\":\"B\"}";
    }

    private String sendBody(Long id, String batchId) {
        return "{\"ids\":[" + id + "],\"batchId\":\"" + batchId + "\"}";
    }

    private String bearer(Employee e) {
        return "Bearer " + jwtService.generateToken(new UserPrincipal(e));
    }

    private Role role(String name, int rank) {
        return roleRepository.findAll().stream().filter(r -> name.equals(r.getName())).findFirst()
                .orElseGet(() -> roleRepository.save(Role.builder().name(name).rank(rank).build()));
    }

    private void grant(Role role, String code, Scope scope) {

        Permission p = permissionRepository.findAll().stream().filter(x -> code.equals(x.getCode())).findFirst()
                .orElseGet(() -> permissionRepository.save(Permission.builder().code(code).build()));

        boolean granted = rolePermissionRepository.findAll().stream()
                .anyMatch(rp -> rp.getRole().getId().equals(role.getId())
                        && rp.getPermission().getId().equals(p.getId()));

        if (!granted) {
            rolePermissionRepository.save(RolePermission.builder().role(role).permission(p).scope(scope).build());
        }
    }

    private Employee employee(String name, String email, String phone, Role role) {
        return employeeRepository.save(Employee.builder()
                .name(name).email(email).phone(phone).password("x").role(role).build());
    }

    private Lead lead(String company, String contact, String email, Employee owner) {
        return leadRepository.save(Lead.builder()
                .companyName(company).contactPerson(contact).email(email).phone("9000000000")
                .leadStatus(LeadStatus.VALID).assignedEmployee(owner).build());
    }
}
