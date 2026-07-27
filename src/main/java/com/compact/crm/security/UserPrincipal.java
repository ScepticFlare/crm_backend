package com.compact.crm.security;

import com.compact.crm.entity.Employee;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Collections;

public class UserPrincipal implements UserDetails {

    private final Employee employee;

    public UserPrincipal(Employee employee) {
        this.employee = employee;
    }

    @Override
    public String getUsername() {
        return employee.getEmail();
    }

    @Override
    public String getPassword() {
        return employee.getPassword();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return Collections.emptyList();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return employee.getIsActive();
    }

    public Employee getEmployee() {
        return employee;
    }
}