package com.compact.crm.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Confirms the Lead email endpoints require a valid token through the real
 * security filter chain, same convention as ActivityLogControllerSecurityTest.
 * Fine-grained (EMAIL_SEND scope) authorization is covered by
 * LeadEmailServiceTest, not here.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LeadEmailControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unauthenticated_cannotPreviewIndividualEmail() throws Exception {

        mockMvc.perform(get("/api/leads/1/email/preview").param("type", "KEEP_IN_TOUCH"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void unauthenticated_cannotSendIndividualEmail() throws Exception {

        mockMvc.perform(post("/api/leads/1/email/send")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void unauthenticated_cannotPreviewBulkEligibility() throws Exception {

        mockMvc.perform(get("/api/leads/email/keep-in-touch/bulk/preview").param("ids", "1,2"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void unauthenticated_cannotSendBulkKeepInTouch() throws Exception {

        mockMvc.perform(post("/api/leads/email/keep-in-touch/bulk")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().is4xxClientError());
    }
}
