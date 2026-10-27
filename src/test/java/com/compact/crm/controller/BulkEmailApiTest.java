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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-level checks (real security chain, JSON binding, validation, error
 * mapping) of /api/bulk-email, with a FAKE email transport.
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
    private Lead mine;
    private Lead theirs;

    @BeforeEach
    void setUp() {

        Role employeeRole = role("EMPLOYEE", 10);
        Role restricted = role("NOEMAIL", 10);

        grant(employeeRole, "EMAIL_SEND", Scope.OWN);

        // a role that was never granted EMAIL_SEND
        grant(restricted, "LEAD_VIEW", Scope.OWN);

        owner = employee("Bulk Owner", "bulk-owner@test.com", "9700000001", employeeRole);
        other = employee("Bulk Other", "bulk-other@test.com", "9700000002", employeeRole);
        noEmail = employee("Bulk NoEmail", "bulk-none@test.com", "9700000003", restricted);

        mine = lead("Mine Co", "Mina", "mina@mine.test", owner);
        theirs = lead("Theirs Co", "Theo", "theo@theirs.test", other);

        when(brevo.trySend(any(), any(), any(), any()))
                .thenReturn(new BrevoEmailClient.SendOutcome(BrevoEmailClient.Result.ACCEPTED, null, null));
    }

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {

        mockMvc.perform(post("/api/bulk-email/preview").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"LEAD\",\"ids\":[1]}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/bulk-email/send").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/bulk-email/batches/abcdefgh-1234"))
                .andExpect(status().isUnauthorized());

        verify(brevo, never()).trySend(any(), any(), any(), any());
    }

    @Test
    void roleWithoutEmailSend_isForbidden() throws Exception {

        mockMvc.perform(post("/api/bulk-email/preview").header("Authorization", bearer(noEmail))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"LEAD\",\"ids\":[" + mine.getId() + "]}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/bulk-email/send").header("Authorization", bearer(noEmail))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody(mine.getId(), "batch-forbidden-1")))
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

        mockMvc.perform(post("/api/bulk-email/send").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody(mine.getId(), "short")))
                .andExpect(status().isBadRequest());

        verify(brevo, never()).trySend(any(), any(), any(), any());
    }

    @Test
    void previewThenSend_returnsPerRecipientOutcomes_andForeignIdsStayExcluded() throws Exception {

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

        mockMvc.perform(post("/api/bulk-email/send").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"LEAD\",\"ids\":[" + mine.getId() + "," + theirs.getId()
                                + "],\"subject\":\"Hi\",\"body\":\"Hello {{contactPerson}}\",\"batchId\":\"batch-api-0001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.excluded").value(1))
                .andExpect(jsonPath("$.results[?(@.id==" + mine.getId() + ")].outcome").value("ACCEPTED"));

        // reload-safe results, and a repeat of the same request does not resend
        mockMvc.perform(get("/api/bulk-email/batches/batch-api-0001").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1));

        mockMvc.perform(post("/api/bulk-email/send").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"LEAD\",\"ids\":[" + mine.getId()
                                + "],\"subject\":\"Hi\",\"body\":\"Hello {{contactPerson}}\",\"batchId\":\"batch-api-0001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].alreadyProcessed").value(true));

        verify(brevo, org.mockito.Mockito.times(1)).trySend(any(), any(), any(), any());

        // another user cannot read or reuse this batch
        mockMvc.perform(get("/api/bulk-email/batches/batch-api-0001").header("Authorization", bearer(other)))
                .andExpect(status().isForbidden());
    }

    // ---------- helpers

    private String sendBody(Long id, String batchId) {
        return "{\"target\":\"LEAD\",\"ids\":[" + id + "],\"subject\":\"S\",\"body\":\"B\",\"batchId\":\"" + batchId + "\"}";
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
