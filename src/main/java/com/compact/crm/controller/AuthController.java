package com.compact.crm.controller;

import com.compact.crm.dto.auth.LoginRequest;
import com.compact.crm.dto.auth.LoginResponse;
import com.compact.crm.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request) {

        return ResponseEntity.ok(authService.authenticate(request));
    }

    // JWTs are stateless - there is nothing server-side to invalidate. This
    // endpoint exists purely to record a LOGOUT activity entry for the
    // authenticated caller before the frontend discards its token.
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {

        authService.recordLogout();

        return ResponseEntity.ok().build();
    }
}