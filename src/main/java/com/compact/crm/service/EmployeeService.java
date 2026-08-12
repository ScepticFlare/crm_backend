package com.compact.crm.service;

import com.compact.crm.dto.request.EmployeeRequest;
import com.compact.crm.entity.Employee;
import com.compact.crm.enums.Role;
import com.compact.crm.exception.ResourceNotFoundException;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class EmployeeService {

    private final EmployeeRepository employeeRepository;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUserService currentUserService;

    public Employee createEmployee(EmployeeRequest request) {

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        if (currentEmployee.getRole() != Role.ADMIN) {
            throw new AccessDeniedException(
                    "Only administrators can create employees.");
        }

        if (request.getPassword() == null || request.getPassword().isBlank()) {
            throw new IllegalArgumentException("Password is required.");
        }

        Employee employee = Employee.builder()
                .name(request.getName())
                .email(request.getEmail())
                .phone(request.getPhone())
                .password(passwordEncoder.encode(request.getPassword()))
                .role(request.getRole())          // <-- FIXED
                .build();

        return employeeRepository.save(employee);
    }

    public List<Employee> getAllEmployees() {

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        if (currentEmployee.getRole() != Role.ADMIN) {
            throw new AccessDeniedException(
                    "Only administrators can view all employees.");
        }

        return employeeRepository.findAll();
    }

    public Employee getEmployeeById(Long id) {

        Employee employee = employeeRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Employee not found"));

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        if (currentEmployee.getRole() != Role.ADMIN &&
                !employee.getId().equals(currentEmployee.getId())) {

            throw new AccessDeniedException(
                    "You are not authorized to view this employee.");
        }

        return employee;
    }

    public Employee updateEmployee(Long id, EmployeeRequest request) {

        Employee employee = employeeRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Employee not found"));

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        if (currentEmployee.getRole() != Role.ADMIN &&
                !employee.getId().equals(currentEmployee.getId())) {

            throw new AccessDeniedException(
                    "You are not authorized to update this employee.");
        }

        employee.setName(request.getName());
        employee.setEmail(request.getEmail());
        employee.setPhone(request.getPhone());

        // <-- NEW
        employee.setRole(request.getRole());

        if (request.getPassword() != null &&
                !request.getPassword().isBlank()) {

            employee.setPassword(
                    passwordEncoder.encode(request.getPassword()));
        }

        return employeeRepository.save(employee);
    }

    public void deleteEmployee(Long id) {

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        if (currentEmployee.getRole() != Role.ADMIN) {
            throw new AccessDeniedException(
                    "Only administrators can delete employees.");
        }


        Employee employee = employeeRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Employee not found"));
        if (currentEmployee.getId().equals(employee.getId())) {
            throw new IllegalArgumentException(
                    "You cannot delete your own account."
            );
        }

        employeeRepository.delete(employee);
    }
}