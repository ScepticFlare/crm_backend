package com.compact.crm.service;

import com.compact.crm.dto.auth.LoginRequest;
import com.compact.crm.dto.auth.LoginResponse;
import com.compact.crm.entity.Employee;
import com.compact.crm.enums.ActivityAction;
import com.compact.crm.enums.ActivityModule;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.security.CurrentUserService;
import com.compact.crm.security.CustomUserDetailsService;
import com.compact.crm.security.JwtService;
import com.compact.crm.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final CustomUserDetailsService userDetailsService;
    private final JwtService jwtService;
    private final EmployeeRepository employeeRepository;
    private final ActivityLogService activityLogService;
    private final CurrentUserService currentUserService;

    public LoginResponse authenticate(LoginRequest request) {

        try {

            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            request.getEmail(),
                            request.getPassword()
                    )
            );

        } catch (AuthenticationException ex) {

            // Only attributable, non-invasive detail is logged: which
            // employee's password was wrong, never the attempted password
            // itself. A nonexistent email has no employee to attribute the
            // attempt to and is intentionally not logged - there's nothing
            // meaningful to record it against without storing the raw
            // attempted email.
            employeeRepository.findByEmail(request.getEmail()).ifPresent(employee ->
                    activityLogService.log(
                            employee,
                            ActivityModule.AUTH, ActivityAction.FAILED_LOGIN,
                            null, null,
                            "Failed login attempt"
                    )
            );

            throw ex;
        }

        UserDetails user =
                userDetailsService.loadUserByUsername(request.getEmail());

        String jwt =
                jwtService.generateToken(user);

        Employee employee =
                ((UserPrincipal) user).getEmployee();

        activityLogService.log(
                employee,
                ActivityModule.AUTH, ActivityAction.LOGIN,
                null, null,
                "User logged in"
        );

        return new LoginResponse(
                jwt,
                employee.getId(),
                employee.getName()
        );
    }

    public void recordLogout() {

        activityLogService.log(
                currentUserService.getCurrentEmployee(),
                ActivityModule.AUTH, ActivityAction.LOGOUT,
                null, null,
                "User logged out"
        );
    }
}