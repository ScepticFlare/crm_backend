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

    public Employee getCurrentEmployee() {

        String email = SecurityContextHolder
                .getContext()
                .getAuthentication()
                .getName();

        return employeeRepository.findByEmail(email)
                .orElseThrow(() ->
                        new RuntimeException("Authenticated employee not found"));
    }

    public boolean isAdmin() {
        return getCurrentEmployee().getRole().name().equals("ADMIN");
    }
}