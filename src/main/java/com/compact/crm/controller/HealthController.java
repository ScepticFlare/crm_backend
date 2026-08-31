package com.compact.crm.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// Public liveness endpoint for Render free-tier cold starts (see
// SecurityConfig - this is the only route other than /api/auth/login that
// is permitAll()). Deliberately does nothing but return a static body: no
// DB/Supabase call, so it can answer as soon as the Spring context is up,
// before the app is otherwise ready to serve real requests.
@RestController
public class HealthController {

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}
