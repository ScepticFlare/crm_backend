package com.compact.crm.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the permanent fix for the stale-JWT login bug: a stale/malformed
 * Bearer token left over from a previous session in localStorage must never
 * stop a fresh login, and must never crash a protected request with an
 * unhandled 500 - see JwtAuthenticationFilter.shouldNotFilter (skips
 * /api/auth/login entirely) and the try/catch around token parsing
 * (treats any parse failure as "not authenticated" instead of propagating
 * JwtException up through the filter chain, where GlobalExceptionHandler's
 * @RestControllerAdvice can never reach it - filters run before the
 * DispatcherServlet's exception handling).
 */
@SpringBootTest
@AutoConfigureMockMvc
class JwtAuthenticationFilterSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void malformedBearerToken_onLoginEndpoint_stillReachesController_insteadOf500() throws Exception {

        // Simulates a browser with a stale/corrupted token still in
        // localStorage attempting a fresh login. Before the fix, this
        // Authorization header would make JwtAuthenticationFilter throw an
        // uncaught JwtException before AuthController.login() ever ran.
        mockMvc.perform(post("/api/auth/login")
                        .header("Authorization", "Bearer not-a-real-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@example.com\",\"password\":\"wrongpassword\"}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void expiredLookingBearerToken_onLoginEndpoint_stillReachesController_insteadOf500() throws Exception {

        // A syntactically JWT-shaped but unsigned/garbage token - closer to
        // "expired/wrong-signature" than the previous test's plain garbage
        // string, still exercised via the same shouldNotFilter path.
        String fakeJwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJnaG9zdCJ9.invalidsignature";

        mockMvc.perform(post("/api/auth/login")
                        .header("Authorization", "Bearer " + fakeJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@example.com\",\"password\":\"wrongpassword\"}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void malformedBearerToken_onProtectedEndpoint_isRejectedCleanly_insteadOf500() throws Exception {

        mockMvc.perform(get("/api/employees")
                        .header("Authorization", "Bearer not-a-real-jwt"))
                .andExpect(status().is4xxClientError());
    }

    /**
     * A request that never authenticated (no token, or an expired/invalid
     * one) must come back as 401, not 403 - see
     * SecurityConfig.exceptionHandling. 403 is reserved for a caller who IS
     * authenticated but is denied by RBAC, so the frontend keys its
     * "session expired, redirect to login" handling off 401 alone.
     */
    @Test
    void noToken_onProtectedEndpoint_returns401_not403() throws Exception {

        mockMvc.perform(get("/api/employees"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidToken_onProtectedEndpoint_returns401_not403() throws Exception {

        mockMvc.perform(get("/api/employees")
                        .header("Authorization", "Bearer not-a-real-jwt"))
                .andExpect(status().isUnauthorized());
    }
}
