package com.compact.crm.controller;

import com.compact.crm.entity.ActivityType;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Industry;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.LeadSourceMaster;
import com.compact.crm.entity.Permission;
import com.compact.crm.entity.Role;
import com.compact.crm.entity.RolePermission;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.LeadValidity;
import com.compact.crm.enums.Scope;
import com.compact.crm.repository.ActivityTypeRepository;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.FollowUpRepository;
import com.compact.crm.repository.IndustryRepository;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.LeadSourceMasterRepository;
import com.compact.crm.repository.OpportunityRepository;
import com.compact.crm.repository.PermissionRepository;
import com.compact.crm.repository.RolePermissionRepository;
import com.compact.crm.repository.RoleRepository;
import com.compact.crm.security.JwtService;
import com.compact.crm.security.UserPrincipal;
import com.compact.crm.util.BusinessClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The mandatory next action cannot be bypassed over HTTP, and a rejected
 * conversion leaves nothing behind. Deliberately NOT @Transactional: the
 * service's own transaction must commit or roll back for real, so the
 * assertions read what is actually persisted. The in-memory test context is
 * discarded afterwards (@DirtiesContext).
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MandatoryNextActionAtomicityApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private RolePermissionRepository rolePermissionRepository;
    @Autowired private IndustryRepository industryRepository;
    @Autowired private LeadSourceMasterRepository leadSourceMasterRepository;
    @Autowired private LeadRepository leadRepository;
    @Autowired private OpportunityRepository opportunityRepository;
    @Autowired private FollowUpRepository followUpRepository;
    @Autowired private ActivityTypeRepository activityTypeRepository;

    private Industry industry;
    private LeadSourceMaster source;
    private ActivityType call;

    @BeforeEach
    void setUp() {
        // non-transactional test: master rows persist between tests, so create once
        industry = industryRepository.findAll().stream().filter(i -> "Atomic Industry".equals(i.getName()))
                .findFirst().orElseGet(() -> industryRepository.save(Industry.builder().name("Atomic Industry").build()));
        source = leadSourceMasterRepository.findAll().stream().filter(i -> "Atomic Source".equals(i.getName()))
                .findFirst().orElseGet(() -> leadSourceMasterRepository.save(LeadSourceMaster.builder().name("Atomic Source").build()));
        call = activityTypeRepository.findAll().stream().filter(i -> "Atomic Call".equals(i.getName()))
                .findFirst().orElseGet(() -> activityTypeRepository.save(ActivityType.builder().name("Atomic Call").build()));
    }

    private Employee employeeWith(String roleName, String email, String phone, List<String> codes) {

        Role role = roleRepository.save(Role.builder().name(roleName).rank(10).build());

        for (String code : codes) {

            Permission p = permissionRepository.findAll().stream()
                    .filter(x -> code.equals(x.getCode())).findFirst()
                    .orElseGet(() -> permissionRepository.save(Permission.builder().code(code).build()));

            rolePermissionRepository.save(RolePermission.builder()
                    .role(role).permission(p).scope(Scope.ALL).build());
        }

        return employeeRepository.save(Employee.builder()
                .name(roleName).email(email).phone(phone).password("x").role(role).build());
    }

    private Lead bareValidLead(String company, Employee owner) {

        return leadRepository.save(Lead.builder()
                .companyName(company).contactPerson("C").phone("1234567890")
                .city("Pune").state("MH").industry(industry).leadSource(source)
                .leadStatus(LeadStatus.VALID).leadValidity(LeadValidity.VALID)
                .assignedEmployee(owner).build());
    }

    private String body(boolean withNextAction) {

        String next = withNextAction
                ? ",\"nextActionDescription\":\"Send the proposal\",\"nextFollowUpDate\":\""
                  + LocalDateTime.now().plusDays(3).withNano(0) + "\",\"nextActionActivityTypeId\":" + call.getId()
                : "";

        return "{\"title\":\"Deal\",\"productValue\":100,\"expectedClosingDate\":\""
                + BusinessClock.today().plusDays(30) + "\",\"salesStage\":\"IN PROGRESS\",\"leadValidity\":\"VALID\""
                + next + "}";
    }

    private String tokenOf(Employee e) {
        return "Bearer " + jwtService.generateToken(new UserPrincipal(e));
    }

    @Test
    void userWithoutFollowUpPermission_cannotBypassTheRule_andNothingIsLeftBehind() throws Exception {

        Employee limited = employeeWith("ATOMIC_LIMITED", "limited@atomic.test", "9300000001",
                List.of("LEAD_VIEW", "LEAD_MANAGE", "OPPORTUNITY_VIEW", "OPPORTUNITY_MANAGE"));

        Lead lead = bareValidLead("Atomic Limited Co", limited);

        long opportunities = opportunityRepository.count();
        long followUps = followUpRepository.count();

        // supplies a next action but may not create follow-ups -> refused ...
        mockMvc.perform(post("/api/opportunities/convert/" + lead.getId())
                        .header("Authorization", tokenOf(limited)).contentType("application/json")
                        .content(body(true)))
                .andExpect(status().isForbidden());

        // ... and the half-done conversion was rolled back for real
        assertThat(opportunityRepository.count()).isEqualTo(opportunities);
        assertThat(followUpRepository.count()).isEqualTo(followUps);
        assertThat(opportunityRepository.findAll().stream()
                .noneMatch(o -> o.getLead().getId().equals(lead.getId()))).isTrue();

        // without a next action the rule itself rejects it
        mockMvc.perform(post("/api/opportunities/convert/" + lead.getId())
                        .header("Authorization", tokenOf(limited)).contentType("application/json")
                        .content(body(false)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("next action")));

        assertThat(opportunityRepository.count()).isEqualTo(opportunities);
        assertThat(followUpRepository.count()).isEqualTo(followUps);
    }

    @Test
    void sameRequestWithFollowUpPermission_succeedsAndPersistsBothTogether() throws Exception {

        Employee full = employeeWith("ATOMIC_FULL", "full@atomic.test", "9300000002",
                List.of("LEAD_VIEW", "LEAD_MANAGE", "OPPORTUNITY_VIEW", "OPPORTUNITY_MANAGE", "FOLLOWUP_MANAGE", "FOLLOWUP_VIEW"));

        Lead lead = bareValidLead("Atomic Full Co", full);

        mockMvc.perform(post("/api/opportunities/convert/" + lead.getId())
                        .header("Authorization", tokenOf(full)).contentType("application/json")
                        .content(body(true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.noNextAction").value(false));

        assertThat(opportunityRepository.findAll().stream()
                .filter(o -> o.getLead().getId().equals(lead.getId())).count()).isEqualTo(1);

        assertThat(followUpRepository.findAll().stream()
                .filter(f -> f.getOpportunity() != null
                        && f.getOpportunity().getLead().getId().equals(lead.getId())
                        && "Send the proposal".equals(f.getRemarks())).count()).isEqualTo(1);
    }
}
