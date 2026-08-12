package com.compact.crm.service;

import com.compact.crm.dto.auth.LoginRequest;
import com.compact.crm.dto.auth.LoginResponse;
import com.compact.crm.entity.Employee;
import com.compact.crm.security.CustomUserDetailsService;
import com.compact.crm.security.JwtService;
import com.compact.crm.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final CustomUserDetailsService userDetailsService;
    private final JwtService jwtService;

    public LoginResponse authenticate(LoginRequest request) {

        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.getEmail(),
                        request.getPassword()
                )
        );

        UserDetails user =
                userDetailsService.loadUserByUsername(request.getEmail());

        String jwt =
                jwtService.generateToken(user);

        Employee employee =
                ((UserPrincipal) user).getEmployee();

        return new LoginResponse(
                jwt,
                employee.getId(),
                employee.getName()
        );
    }
}