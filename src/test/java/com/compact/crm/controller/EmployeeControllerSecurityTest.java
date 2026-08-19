package com.compact.crm.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /api/employees/** previously allowed unauthenticated access entirely
 * (SecurityConfig.permitAll()). Confirms the whole real security filter
 * chain (not a mocked slice) now rejects every one of these endpoints
 * without a valid token, the same as any other protected resource.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EmployeeControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unauthenticated_cannotListEmployees() throws Exception {

        mockMvc.perform(get("/api/employees"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void unauthenticated_cannotGetEmployeeById() throws Exception {

        mockMvc.perform(get("/api/employees/1"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void unauthenticated_cannotCreateEmployee() throws Exception {

        mockMvc.perform(post("/api/employees")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Eve\",\"email\":\"eve@example.com\",\"phone\":\"9876543210\",\"password\":\"password123\",\"role\":\"ADMIN\"}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void unauthenticated_cannotUpdateEmployee() throws Exception {

        mockMvc.perform(put("/api/employees/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Eve\",\"email\":\"eve@example.com\",\"phone\":\"9876543210\",\"role\":\"ADMIN\"}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void unauthenticated_cannotDeleteEmployee() throws Exception {

        mockMvc.perform(delete("/api/employees/1"))
                .andExpect(status().is4xxClientError());
    }
}
