package com.compact.crm.controller;

import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Role;
import com.compact.crm.entity.SalesStage;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.RoleRepository;
import com.compact.crm.repository.SalesStageRepository;
import com.compact.crm.security.JwtService;
import com.compact.crm.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack (real security filter chain, real JWTs, real H2) check that
 * sales-stage configuration is ADMIN-only on the server, while every
 * authenticated role can still read the stage list to work on
 * Opportunities.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SalesStageControllerSecurityTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private SalesStageRepository salesStageRepository;

    private String adminToken;
    private String managerToken;
    private String employeeToken;
    private Long stageId;

    @BeforeEach
    void setUp() {

        adminToken = tokenFor("ADMIN", 100, "stage-admin@test.com", "9100000001");
        managerToken = tokenFor("MANAGER", 50, "stage-manager@test.com", "9100000002");
        employeeToken = tokenFor("EMPLOYEE", 10, "stage-employee@test.com", "9100000003");

        stageId = salesStageRepository.save(SalesStage.builder().name("SECURITY TEST STAGE").build()).getId();
    }

    private String tokenFor(String roleName, int rank, String email, String phone) {

        Role role = roleRepository.findAll().stream()
                .filter(r -> roleName.equals(r.getName()))
                .findFirst()
                .orElseGet(() -> roleRepository.save(Role.builder().name(roleName).rank(rank).build()));

        Employee employee = employeeRepository.save(Employee.builder()
                .name(roleName + " user").email(email).phone(phone).password("x").role(role).build());

        return "Bearer " + jwtService.generateToken(new UserPrincipal(employee));
    }

    private static final String BODY = "{\"name\":\"BRAND NEW STAGE\"}";

    @Test
    void employeeAndManager_cannotCreateStage() throws Exception {

        for (String token : new String[]{employeeToken, managerToken}) {

            mockMvc.perform(post("/api/sales-stages")
                            .header("Authorization", token)
                            .contentType("application/json").content(BODY))
                    .andExpect(status().isForbidden());
        }

        assertThat(salesStageRepository.findByNameIgnoreCase("BRAND NEW STAGE")).isEmpty();
    }

    @Test
    void employeeAndManager_cannotRenameStage() throws Exception {

        for (String token : new String[]{employeeToken, managerToken}) {

            mockMvc.perform(put("/api/sales-stages/" + stageId)
                            .header("Authorization", token)
                            .contentType("application/json").content("{\"name\":\"RENAMED\"}"))
                    .andExpect(status().isForbidden());
        }

        assertThat(salesStageRepository.findById(stageId).orElseThrow().getName()).isEqualTo("SECURITY TEST STAGE");
    }

    @Test
    void employeeAndManager_cannotDeleteOrActivateStage() throws Exception {

        for (String token : new String[]{employeeToken, managerToken}) {

            mockMvc.perform(delete("/api/sales-stages/" + stageId).header("Authorization", token))
                    .andExpect(status().isForbidden());

            mockMvc.perform(put("/api/sales-stages/" + stageId + "/activate").header("Authorization", token))
                    .andExpect(status().isForbidden());

            mockMvc.perform(get("/api/sales-stages/all").header("Authorization", token))
                    .andExpect(status().isForbidden());
        }

        assertThat(salesStageRepository.findById(stageId).orElseThrow().getIsActive()).isTrue();
    }

    @Test
    void unauthenticated_cannotCreateStage() throws Exception {

        mockMvc.perform(post("/api/sales-stages").contentType("application/json").content(BODY))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void admin_canCreateRenameDeactivateAndActivateStage() throws Exception {

        mockMvc.perform(post("/api/sales-stages")
                        .header("Authorization", adminToken)
                        .contentType("application/json").content(BODY))
                .andExpect(status().isOk());

        assertThat(salesStageRepository.findByNameIgnoreCase("BRAND NEW STAGE")).isPresent();

        mockMvc.perform(put("/api/sales-stages/" + stageId)
                        .header("Authorization", adminToken)
                        .contentType("application/json").content("{\"name\":\"RENAMED\"}"))
                .andExpect(status().isOk());

        assertThat(salesStageRepository.findById(stageId).orElseThrow().getName()).isEqualTo("RENAMED");

        mockMvc.perform(delete("/api/sales-stages/" + stageId).header("Authorization", adminToken))
                .andExpect(status().isOk());

        assertThat(salesStageRepository.findById(stageId).orElseThrow().getIsActive()).isFalse();

        mockMvc.perform(put("/api/sales-stages/" + stageId + "/activate").header("Authorization", adminToken))
                .andExpect(status().isOk());

        assertThat(salesStageRepository.findById(stageId).orElseThrow().getIsActive()).isTrue();
    }

    @Test
    void everyRole_canStillReadTheStageList() throws Exception {

        for (String token : new String[]{adminToken, managerToken, employeeToken}) {

            mockMvc.perform(get("/api/sales-stages").header("Authorization", token))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/api/sales-stages/" + stageId).header("Authorization", token))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void admin_canRenameACoreStage_butCannotDeactivateIt() throws Exception {

        Long wonId = salesStageRepository.findByStageType(com.compact.crm.enums.SalesStageType.WON).get(0).getId();

        mockMvc.perform(put("/api/sales-stages/" + wonId)
                        .header("Authorization", adminToken)
                        .contentType("application/json").content("{\"name\":\"Order Won\"}"))
                .andExpect(status().isOk());

        SalesStage renamed = salesStageRepository.findById(wonId).orElseThrow();
        assertThat(renamed.getName()).isEqualTo("Order Won");
        assertThat(renamed.getStageType()).isEqualTo(com.compact.crm.enums.SalesStageType.WON);

        mockMvc.perform(delete("/api/sales-stages/" + wonId).header("Authorization", adminToken))
                .andExpect(status().isBadRequest());

        assertThat(salesStageRepository.findById(wonId).orElseThrow().getIsActive()).isTrue();
    }

    @Test
    void renameToADuplicateName_isABadRequest_notAServerError() throws Exception {

        mockMvc.perform(put("/api/sales-stages/" + stageId)
                        .header("Authorization", adminToken)
                        .contentType("application/json").content("{\"name\":\"lost\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/sales-stages/" + stageId)
                        .header("Authorization", adminToken)
                        .contentType("application/json").content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }
}
