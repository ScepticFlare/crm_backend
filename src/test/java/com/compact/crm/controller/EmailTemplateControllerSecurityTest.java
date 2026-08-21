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
 * Confirms /api/email-templates/** requires a valid token through the real
 * security filter chain, same convention as ActivityLogControllerSecurityTest.
 * Fine-grained (EMAIL_TEMPLATE_MANAGE) authorization is covered by
 * EmailTemplateServiceTest, not here.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EmailTemplateControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unauthenticated_cannotListTemplates() throws Exception {

        mockMvc.perform(get("/api/email-templates"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void unauthenticated_cannotCreateTemplate() throws Exception {

        mockMvc.perform(post("/api/email-templates")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().is4xxClientError());
    }
}
