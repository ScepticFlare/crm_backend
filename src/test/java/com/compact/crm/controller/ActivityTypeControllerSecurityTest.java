package com.compact.crm.controller;

import com.compact.crm.entity.ActivityType;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.FollowUp;
import com.compact.crm.entity.Industry;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.LeadSourceMaster;
import com.compact.crm.entity.Permission;
import com.compact.crm.entity.Role;
import com.compact.crm.entity.RolePermission;
import com.compact.crm.enums.FollowUpStatus;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.LeadValidity;
import com.compact.crm.enums.Scope;
import com.compact.crm.repository.ActivityTypeRepository;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.FollowUpRepository;
import com.compact.crm.repository.IndustryRepository;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.LeadSourceMasterRepository;
import com.compact.crm.repository.PermissionRepository;
import com.compact.crm.repository.RolePermissionRepository;
import com.compact.crm.repository.RoleRepository;
import com.compact.crm.security.JwtService;
import com.compact.crm.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack (real security filter chain, real JWTs, real H2) check that
 * activity-type configuration is ADMIN-only on the server - the same rule as
 * sales stages - while every role can still read the active list, and that a
 * deactivated activity type is no longer selectable for a new follow-up but
 * stays on (and keeps displaying for) the follow-ups that already use it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ActivityTypeControllerSecurityTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private RolePermissionRepository rolePermissionRepository;
    @Autowired private ActivityTypeRepository activityTypeRepository;
    @Autowired private FollowUpRepository followUpRepository;
    @Autowired private LeadRepository leadRepository;
    @Autowired private IndustryRepository industryRepository;
    @Autowired private LeadSourceMasterRepository leadSourceMasterRepository;

    private Employee admin;
    private String adminToken;
    private String managerToken;
    private String employeeToken;
    private Long activityId;

    @BeforeEach
    void setUp() {

        Role adminRole = role("ADMIN", 100);

        for (String code : new String[]{"FOLLOWUP_VIEW", "FOLLOWUP_MANAGE"}) {
            grantAll(adminRole, code);
        }

        admin = employee("act-admin@test.com", "9110000001", adminRole);
        adminToken = token(admin);
        managerToken = token(employee("act-manager@test.com", "9110000002", role("MANAGER", 50)));
        employeeToken = token(employee("act-employee@test.com", "9110000003", role("EMPLOYEE", 10)));

        activityId = activityTypeRepository.save(ActivityType.builder().name("Security Test Visit").build()).getId();
    }

    private Role role(String name, int rank) {
        return roleRepository.findAll().stream()
                .filter(r -> name.equals(r.getName())).findFirst()
                .orElseGet(() -> roleRepository.save(Role.builder().name(name).rank(rank).build()));
    }

    private void grantAll(Role role, String code) {

        Permission p = permissionRepository.findAll().stream()
                .filter(x -> code.equals(x.getCode())).findFirst()
                .orElseGet(() -> permissionRepository.save(Permission.builder().code(code).build()));

        boolean granted = rolePermissionRepository.findAll().stream()
                .anyMatch(rp -> rp.getRole().getId().equals(role.getId()) && rp.getPermission().getId().equals(p.getId()));

        if (!granted) {
            rolePermissionRepository.save(RolePermission.builder().role(role).permission(p).scope(Scope.ALL).build());
        }
    }

    private Employee employee(String email, String phone, Role role) {
        return employeeRepository.save(Employee.builder()
                .name(role.getName() + " user").email(email).phone(phone).password("x").role(role).build());
    }

    private String token(Employee e) {
        return "Bearer " + jwtService.generateToken(new UserPrincipal(e));
    }

    private Lead lead() {
        Industry industry = industryRepository.save(Industry.builder().name("Act Industry").build());
        LeadSourceMaster source = leadSourceMasterRepository.save(LeadSourceMaster.builder().name("Act Source").build());
        return leadRepository.save(Lead.builder()
                .companyName("Act Co").contactPerson("C").phone("9811100000")
                .industry(industry).leadSource(source)
                .leadStatus(LeadStatus.NEW).leadValidity(LeadValidity.VALID)
                .assignedEmployee(admin).build());
    }

    private String followUpBody(Long leadId, Long activityTypeId) {
        return "{\"leadId\":" + leadId + ",\"employeeId\":" + admin.getId()
                + ",\"activityTypeId\":" + activityTypeId + ",\"status\":\"PENDING\""
                + ",\"scheduledDate\":\"" + LocalDateTime.now().plusDays(3).withNano(0) + "\""
                + ",\"remarks\":\"Call back about the quotation\"}";
    }

    private static final String BODY = "{\"name\":\"Brand New Activity\"}";

    // ---------------------------------------------------------------- authorization

    @Test
    void employeeAndManager_cannotCreateRenameDeactivateOrActivate() throws Exception {

        for (String token : new String[]{employeeToken, managerToken}) {

            mockMvc.perform(post("/api/activity-types").header("Authorization", token)
                            .contentType("application/json").content(BODY))
                    .andExpect(status().isForbidden());

            mockMvc.perform(put("/api/activity-types/" + activityId).header("Authorization", token)
                            .contentType("application/json").content("{\"name\":\"Renamed\"}"))
                    .andExpect(status().isForbidden());

            mockMvc.perform(delete("/api/activity-types/" + activityId).header("Authorization", token))
                    .andExpect(status().isForbidden());

            mockMvc.perform(put("/api/activity-types/" + activityId + "/activate").header("Authorization", token))
                    .andExpect(status().isForbidden());

            mockMvc.perform(get("/api/activity-types/all").header("Authorization", token))
                    .andExpect(status().isForbidden());
        }

        ActivityType unchanged = activityTypeRepository.findById(activityId).orElseThrow();
        assertThat(unchanged.getName()).isEqualTo("Security Test Visit");
        assertThat(unchanged.getIsActive()).isTrue();
        assertThat(activityTypeRepository.findByNameIgnoreCase("Brand New Activity")).isEmpty();
    }

    @Test
    void unauthenticated_cannotCreate() throws Exception {
        mockMvc.perform(post("/api/activity-types").contentType("application/json").content(BODY))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void everyRole_canReadTheActiveList() throws Exception {

        for (String token : new String[]{adminToken, managerToken, employeeToken}) {
            mockMvc.perform(get("/api/activity-types").header("Authorization", token))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void admin_canCreateRenameDeactivateAndReactivate() throws Exception {

        mockMvc.perform(post("/api/activity-types").header("Authorization", adminToken)
                        .contentType("application/json").content(BODY))
                .andExpect(status().isOk());
        assertThat(activityTypeRepository.findByNameIgnoreCase("Brand New Activity")).isPresent();

        mockMvc.perform(put("/api/activity-types/" + activityId).header("Authorization", adminToken)
                        .contentType("application/json").content("{\"name\":\"Site Visit\"}"))
                .andExpect(status().isOk());
        assertThat(activityTypeRepository.findById(activityId).orElseThrow().getName()).isEqualTo("Site Visit");

        // deactivated: gone from the selectable list, still in the admin list, row kept
        mockMvc.perform(delete("/api/activity-types/" + activityId).header("Authorization", adminToken))
                .andExpect(status().isOk());
        assertThat(activityTypeRepository.findById(activityId)).isPresent();
        assertThat(activityTypeRepository.findById(activityId).orElseThrow().getIsActive()).isFalse();

        mockMvc.perform(get("/api/activity-types").header("Authorization", employeeToken))
                .andExpect(jsonPath("$[?(@.id == " + activityId + ")]").isEmpty());
        mockMvc.perform(get("/api/activity-types/all").header("Authorization", adminToken))
                .andExpect(jsonPath("$[?(@.id == " + activityId + ")]").isNotEmpty());

        mockMvc.perform(put("/api/activity-types/" + activityId + "/activate").header("Authorization", adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/activity-types").header("Authorization", employeeToken))
                .andExpect(jsonPath("$[?(@.id == " + activityId + ")]").isNotEmpty());
    }

    @Test
    void renameToADuplicateOrBlankName_isABadRequest_notAServerError() throws Exception {

        activityTypeRepository.save(ActivityType.builder().name("Security Test Demo").build());

        mockMvc.perform(put("/api/activity-types/" + activityId).header("Authorization", adminToken)
                        .contentType("application/json").content("{\"name\":\"security test demo\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/activity-types/" + activityId).header("Authorization", adminToken)
                        .contentType("application/json").content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest());

        assertThat(activityTypeRepository.findById(activityId).orElseThrow().getName()).isEqualTo("Security Test Visit");
    }

    // ---------------------------------------------- deactivation and follow-ups

    @Test
    void aDeactivatedType_cannotBeUsedForANewFollowUp_butExistingFollowUpsKeepIt() throws Exception {

        Lead lead = lead();

        FollowUp existing = followUpRepository.save(FollowUp.builder()
                .lead(lead).employee(admin)
                .activityType(activityTypeRepository.findById(activityId).orElseThrow())
                .status(FollowUpStatus.PENDING).scheduledDate(LocalDateTime.now().plusDays(2))
                .remarks("Existing visit").build());

        mockMvc.perform(delete("/api/activity-types/" + activityId).header("Authorization", adminToken))
                .andExpect(status().isOk());

        // a new follow-up cannot pick it (not even through the API)
        mockMvc.perform(post("/api/followups").header("Authorization", adminToken)
                        .contentType("application/json").content(followUpBody(lead.getId(), activityId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Activity type \"Security Test Visit\" is inactive and cannot be selected."));

        // the existing follow-up still shows it...
        mockMvc.perform(get("/api/followups/" + existing.getId()).header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activityType.name").value("Security Test Visit"));

        // ...and can still be edited without having to change it
        mockMvc.perform(put("/api/followups/" + existing.getId()).header("Authorization", adminToken)
                        .contentType("application/json").content(followUpBody(lead.getId(), activityId)))
                .andExpect(status().isOk());

        // but cannot be switched TO another inactive type
        Long otherInactive = activityTypeRepository.save(ActivityType.builder().name("Security Test Fax").build()).getId();
        mockMvc.perform(delete("/api/activity-types/" + otherInactive).header("Authorization", adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/followups/" + existing.getId()).header("Authorization", adminToken)
                        .contentType("application/json").content(followUpBody(lead.getId(), otherInactive)))
                .andExpect(status().isBadRequest());

        assertThat(followUpRepository.findById(existing.getId()).orElseThrow().getActivityType().getId())
                .isEqualTo(activityId);

        // re-activated -> selectable again
        mockMvc.perform(put("/api/activity-types/" + activityId + "/activate").header("Authorization", adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/followups").header("Authorization", adminToken)
                        .contentType("application/json").content(followUpBody(lead.getId(), activityId)))
                .andExpect(status().isOk());
    }

    @Test
    void thereIsNoPermanentDelete_deleteOnlyDeactivates_soReferencedRowsSurvive() throws Exception {

        Lead lead = lead();
        followUpRepository.save(FollowUp.builder()
                .lead(lead).employee(admin)
                .activityType(activityTypeRepository.findById(activityId).orElseThrow())
                .status(FollowUpStatus.COMPLETED).scheduledDate(LocalDateTime.now().minusDays(1))
                .remarks("Done").build());

        mockMvc.perform(delete("/api/activity-types/" + activityId).header("Authorization", adminToken))
                .andExpect(status().isOk());

        assertThat(activityTypeRepository.findById(activityId)).isPresent();
        assertThat(followUpRepository.findAll()).anyMatch(f -> f.getActivityType().getId().equals(activityId));
    }
}
