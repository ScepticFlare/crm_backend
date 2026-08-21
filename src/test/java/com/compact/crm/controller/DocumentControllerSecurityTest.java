package com.compact.crm.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Confirms /api/documents/** requires a valid token through the real
 * security filter chain, same convention as ActivityLogControllerSecurityTest.
 * Fine-grained (DOCUMENT_MANAGE) authorization is covered by
 * DocumentServiceTest, not here.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DocumentControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unauthenticated_cannotListDocuments() throws Exception {

        mockMvc.perform(get("/api/documents"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void unauthenticated_cannotDownloadDocument() throws Exception {

        mockMvc.perform(get("/api/documents/1/download"))
                .andExpect(status().is4xxClientError());
    }
}
