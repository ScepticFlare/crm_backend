package com.compact.crm.controller;

import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Industry;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.Permission;
import com.compact.crm.entity.Product;
import com.compact.crm.entity.Role;
import com.compact.crm.entity.RolePermission;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.Scope;
import com.compact.crm.repository.ActivityLogRepository;
import com.compact.crm.repository.EmailBulkBatchRepository;
import com.compact.crm.repository.EmailSendLogRepository;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.IndustryRepository;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.PermissionRepository;
import com.compact.crm.repository.ProductRepository;
import com.compact.crm.repository.RolePermissionRepository;
import com.compact.crm.repository.RoleRepository;
import com.compact.crm.security.JwtService;
import com.compact.crm.security.UserPrincipal;
import com.compact.crm.service.BrevoEmailClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The CRM as it runs locally without the optional integrations: no Brevo
 * API key, no sender address, no public-lead assignee. The application
 * context must start (the rest of the CRM works), and each feature that
 * needs a missing integration must refuse with 503 - never report an email
 * as sent or a public enquiry as received, and leave nothing behind.
 * Authentication and authorization still apply first.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "brevo.api-key=",
        "crm.mail.from-address=",
        "crm.public-lead.assignee-email=",
        "crm.public-lead.rate-limit.max-requests=1000"
})
@Transactional
class OptionalIntegrationsUnconfiguredTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtService jwtService;
    @Autowired private BrevoEmailClient brevoEmailClient;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private RolePermissionRepository rolePermissionRepository;
    @Autowired private LeadRepository leadRepository;
    @Autowired private IndustryRepository industryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private EmailBulkBatchRepository batchRepository;
    @Autowired private EmailSendLogRepository sendLogRepository;
    @Autowired private ActivityLogRepository activityLogRepository;

    private Employee admin;
    private Lead lead;

    @BeforeEach
    void setUp() {

        Role adminRole = roleRepository.findAll().stream().filter(r -> "ADMIN".equals(r.getName())).findFirst()
                .orElseGet(() -> roleRepository.save(Role.builder().name("ADMIN").rank(100).build()));

        grant(adminRole, "EMAIL_SEND", Scope.ALL);

        admin = employeeRepository.save(Employee.builder()
                .name("Unconfigured Admin").email("unconfigured-admin@test.com").phone("9700000099")
                .password("x").role(adminRole).build());

        lead = leadRepository.save(Lead.builder()
                .companyName("Local Co").contactPerson("Lina").email("lina@local.test").phone("9000000000")
                .leadStatus(LeadStatus.VALID).assignedEmployee(admin).build());
    }

    @Test
    void theApplicationStarts_andTheRestOfTheCrmWorks() throws Exception {

        assertThat(brevoEmailClient.isConfigured()).isFalse();

        mockMvc.perform(get("/health")).andExpect(status().isOk());

        mockMvc.perform(get("/api/bulk-email/access").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canSend").value(true));

        // previewing needs no provider, so it still works
        mockMvc.perform(post("/api/bulk-email/preview")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"LEAD\",\"ids\":[" + lead.getId() + "],\"subject\":\"S\",\"body\":\"B\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void individualEmail_isRefusedWith503_andNothingIsLoggedAsSent() throws Exception {

        long activityBefore = activityLogRepository.count();

        mockMvc.perform(post("/api/leads/" + lead.getId() + "/email/send")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"KEEP_IN_TOUCH\",\"subject\":\"Hello\",\"body\":\"Hi there\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(
                        "Email sending is not configured on this server. Please contact an administrator."));

        assertThat(activityLogRepository.count()).isEqualTo(activityBefore);
    }

    @Test
    void bulkKeepInTouch_isRefusedAsAWhole_notReportedPerLead() throws Exception {

        mockMvc.perform(post("/api/leads/email/keep-in-touch/bulk")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leadIds\":[" + lead.getId() + "],\"subject\":\"Hello\",\"body\":\"Hi\"}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void bulkEmail_cannotStartABatch_andRecordsNoSendAttempt() throws Exception {

        long batchesBefore = batchRepository.count();
        long logsBefore = sendLogRepository.count();

        mockMvc.perform(post("/api/bulk-email/batches")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"LEAD\",\"ids\":[" + lead.getId() + "],\"subject\":\"S\",\"body\":\"B\"}"))
                .andExpect(status().isServiceUnavailable());

        assertThat(batchRepository.count()).isEqualTo(batchesBefore);
        assertThat(sendLogRepository.count()).isEqualTo(logsBefore);
    }

    @Test
    void emailEndpoints_stillRequireAuthenticationFirst() throws Exception {

        mockMvc.perform(post("/api/leads/" + lead.getId() + "/email/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"KEEP_IN_TOUCH\",\"subject\":\"Hello\",\"body\":\"Hi there\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publicLeadSubmission_isRefusedWith503_andCreatesNoLead() throws Exception {

        Industry industry = industryRepository.save(Industry.builder().name("Unconfigured Industry").build());
        Product product = productRepository.save(Product.builder().name("Unconfigured Product").build());

        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("companyName", "Acme Corp");
        payload.put("contactPerson", "Jane Doe");
        payload.put("phone", "9876543210");
        payload.put("email", "jane@acme.com");
        payload.put("city", "Pune");
        payload.put("state", "Maharashtra");
        payload.put("industryId", industry.getId());
        payload.put("description", "Need a quote for 5 units.");
        payload.put("products", List.of(Map.of("productId", product.getId())));
        payload.put("source", "website");

        long leadsBefore = leadRepository.count();

        mockMvc.perform(post("/api/public/leads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(
                        "The enquiry form is temporarily unavailable. Please try again later."))
                .andExpect(jsonPath("$.success").doesNotExist());

        assertThat(leadRepository.count()).isEqualTo(leadsBefore);
    }

    private String bearer(Employee e) {
        return "Bearer " + jwtService.generateToken(new UserPrincipal(e));
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
}
