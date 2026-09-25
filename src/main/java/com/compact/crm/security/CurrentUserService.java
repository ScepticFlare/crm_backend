package com.compact.crm.security;

import com.compact.crm.entity.Employee;
import com.compact.crm.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CurrentUserService {

    private final EmployeeRepository employeeRepository;

    // JwtAuthenticationFilter already loaded this request's Employee once
    // (via CustomUserDetailsService.loadUserByUsername) to authenticate the
    // request, and that Employee is sitting right there on the
    // Authentication principal. Every call site here used to re-run the
    // exact same "findByEmail" query - a second full DB round trip (plus
    // Employee's eager role join) on every single authenticated request,
    // regardless of what the request actually needed. Reading it off the
    // principal instead is free. Only the two call sites that never touch
    // employee.getManager() rely on this (verified: EmployeeService is the
    // only caller of that lazy field, and always on a freshly-loaded
    // Employee, never on this cached one) - see UserPrincipal.
    public Employee getCurrentEmployee() {

        Object principal = SecurityContextHolder
                .getContext()
                .getAuthentication()
                .getPrincipal();

        if (principal instanceof UserPrincipal userPrincipal) {
            return userPrincipal.getEmployee();
        }

        String email = SecurityContextHolder
                .getContext()
                .getAuthentication()
                .getName();

        return employeeRepository.findByEmail(email)
                .orElseThrow(() ->
                        new RuntimeException("Authenticated employee not found"));
    }

    public boolean isAdmin() {
        return getCurrentEmployee().getRole().getName().equals("ADMIN");
    }
}