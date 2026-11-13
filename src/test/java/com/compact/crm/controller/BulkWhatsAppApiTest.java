package com.compact.crm.controller;

import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.Permission;
import com.compact.crm.entity.Role;
import com.compact.crm.entity.RolePermission;
import com.compact.crm.entity.WhatsAppTemplate;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.Scope;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.PermissionRepository;
import com.compact.crm.repository.RolePermissionRepository;
import com.compact.crm.repository.RoleRepository;
import com.compact.crm.repository.WhatsAppTemplateRepository;
import com.compact.crm.security.JwtService;
import com.compact.crm.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP level (real security chain, controllers, H2) with WAHA NOT configured - the
 * state every environment is in until an administrator sets it up. Nothing can
 * reach a WhatsApp provider from here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "crm.whatsapp.waha.webhook-hmac-key=api-test-secret")
class BulkWhatsAppApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private RolePermissionRepository rolePermissionRepository;
    @Autowired private LeadRepository leadRepository;
    @Autowired private WhatsAppTemplateRepository templateRepository;

    private Employee staff;
    private Employee plain;
    private Lead lead;
    private WhatsAppTemplate template;

    @BeforeEach
    void setUp() {

        Role employeeRole = role("WA_EMPLOYEE");
        Role noAccess = role("WA_NONE");

        for (String code : List.of("WHATSAPP_SEND", "LEAD_VIEW", "LEAD_MANAGE")) {
            grant(employeeRole, code, Scope.OWN);
        }

        staff = employeeRepository.save(Employee.builder().name("WA Staff").email("wa-staff@test.com")
                .phone("9311111111").password("x").role(employeeRole).build());
        plain = employeeRepository.save(Employee.builder().name("WA Plain").email("wa-plain@test.com")
                .phone("9311111112").password("x").role(noAccess).build());

        lead = leadRepository.save(Lead.builder().companyName("WA Co").contactPerson("Ana").phone("9876511111")
                .leadStatus(LeadStatus.VALID).assignedEmployee(staff).build());

        template = templateRepository.save(WhatsAppTemplate.builder().name("T").body("Hi {{contactPerson}}")
                .version(1).isActive(true).build());
    }

    @Test
    void everyBulkEndpointNeedsALogin() throws Exception {

        mockMvc.perform(get("/api/bulk-whatsapp/access")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/bulk-whatsapp/preview").contentType(MediaType.APPLICATION_JSON)
                .content("{\"target\":\"LEAD\",\"ids\":[1]}")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/whatsapp-templates")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/whatsapp-consent")).andExpect(status().isUnauthorized());
    }

    @Test
    void access_reportsNotConfigured_andTheCrmOtherwiseWorks() throws Exception {

        mockMvc.perform(get("/api/bulk-whatsapp/access").header("Authorization", bearer(staff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canSend").value(true))
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("not configured")));

        mockMvc.perform(get("/api/bulk-whatsapp/access").header("Authorization", bearer(plain)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canSend").value(false))
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void previewWorks_butCreatingABatchIs503WhileWahaIsNotConfigured() throws Exception {

        String body = "{\"target\":\"LEAD\",\"ids\":[" + lead.getId() + "],\"templateId\":" + template.getId() + "}";

        mockMvc.perform(post("/api/bulk-whatsapp/preview").header("Authorization", bearer(staff))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerReady").value(false))
                .andExpect(jsonPath("$.recipients[0].exclusionCode").value("NO_OPT_IN"));

        mockMvc.perform(post("/api/bulk-whatsapp/batches").header("Authorization", bearer(staff))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("not configured")));
    }

    @Test
    void withoutThePermission_bulkWhatsAppIs403() throws Exception {

        mockMvc.perform(post("/api/bulk-whatsapp/preview").header("Authorization", bearer(plain))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"LEAD\",\"ids\":[" + lead.getId() + "]}"))
                .andExpect(status().isForbidden());

        // template and consent administration are admin-only
        mockMvc.perform(get("/api/whatsapp-templates/all").header("Authorization", bearer(staff)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/whatsapp-consent/bulk-opt-in").header("Authorization", bearer(staff))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phones\":[\"9876511111\"],\"source\":\"x\",\"evidence\":\"y\",\"consentedAt\":\"2026-10-01\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anEmployeeCanRecordAnOptInOnTheirOwnLead() throws Exception {

        mockMvc.perform(post("/api/leads/" + lead.getId() + "/whatsapp-consent").header("Authorization", bearer(staff))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"OPTED_IN\",\"source\":\"Phone call\",\"evidence\":\"Call 3 Nov\","
                                + "\"consentedAt\":\"2026-11-03\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPTED_IN"))
                .andExpect(jsonPath("$.phone").doesNotExist())
                .andExpect(jsonPath("$.phoneMasked").value("+91 ******1111"));
    }

    // ---------------- webhook (public, HMAC-protected) ----------------

    @Test
    void theWebhookIsReachableWithoutALogin_butOnlyAcceptsSignedEvents() throws Exception {

        byte[] body = "{\"event\":\"session.status\",\"session\":\"default\",\"payload\":{\"status\":\"WORKING\"}}"
                .getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/api/public/whatsapp/webhook").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/public/whatsapp/webhook").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Webhook-Hmac", "00ff").header("X-Webhook-Hmac-Algorithm", "sha512"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/public/whatsapp/webhook").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Webhook-Hmac", hmac("api-test-secret", body))
                        .header("X-Webhook-Hmac-Algorithm", "sha512"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ignored"));
    }

    @Test
    void otherPublicPathsAreNotOpenedByTheWebhookRule() throws Exception {
        mockMvc.perform(get("/api/public/whatsapp/webhook")).andExpect(status().isUnauthorized());
    }

    // ---------------- helpers ----------------

    private static String hmac(String key, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }

    private String bearer(Employee e) {
        return "Bearer " + jwtService.generateToken(new UserPrincipal(e));
    }

    private Role role(String name) {
        return roleRepository.findAll().stream().filter(r -> name.equals(r.getName())).findFirst()
                .orElseGet(() -> roleRepository.save(Role.builder().name(name).rank(10).build()));
    }

    private void grant(Role role, String code, Scope scope) {

        Permission p = permissionRepository.findAll().stream().filter(x -> code.equals(x.getCode())).findFirst()
                .orElseGet(() -> permissionRepository.save(Permission.builder().code(code).build()));

        rolePermissionRepository.save(RolePermission.builder().role(role).permission(p).scope(scope).build());
    }
}
