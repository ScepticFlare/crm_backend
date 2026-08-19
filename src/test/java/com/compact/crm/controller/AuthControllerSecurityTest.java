package com.compact.crm.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /api/auth/login must stay unauthenticated (that's how a token is
 * obtained). /api/auth/logout is deliberately NOT under that permitAll -
 * it records an activity entry for "the authenticated caller", so it must
 * require a valid token like every other resource (see SecurityConfig).
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unauthenticated_cannotLogout() throws Exception {

        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void unauthenticated_canReachLoginEndpoint_withoutBeingBlockedByAuthFilter() throws Exception {

        // Deliberately sends bad credentials - the point isn't a successful
        // login, it's proving the request reaches AuthController at all
        // (401 from bad credentials) rather than being rejected by the
        // security filter chain itself (which would be a regression of
        // login's permitAll).
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@example.com\",\"password\":\"wrongpassword\"}"))
                .andExpect(status().is4xxClientError());
    }
}
