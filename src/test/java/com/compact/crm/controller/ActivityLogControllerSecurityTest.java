package com.compact.crm.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Confirms /api/activity/** requires a valid token through the real
 * security filter chain, same convention as EmployeeControllerSecurityTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ActivityLogControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unauthenticated_cannotListActivity() throws Exception {

        mockMvc.perform(get("/api/activity"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void unauthenticated_cannotGetActivitySummary() throws Exception {

        mockMvc.perform(get("/api/activity/summary"))
                .andExpect(status().is4xxClientError());
    }
}
