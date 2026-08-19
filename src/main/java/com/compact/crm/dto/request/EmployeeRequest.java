package com.compact.crm.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeRequest {

    @NotBlank(message = "Employee name is required")
    private String name;

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    @NotBlank(message = "Phone number is required")
    @Size(min = 10, max = 10, message = "Phone number must be exactly 10 digits")
    private String phone;

    @Size(min = 8, message = "Password must be at least 8 characters long")
    private String password;

    // Role name (ADMIN / MANAGER / EMPLOYEE), resolved against the roles
    // table in EmployeeService. String rather than an entity reference so
    // this DTO stays a plain JSON shape, same as every other request DTO.
    @NotBlank(message = "Role is required")
    private String role;

    // Direct manager's employee id, or null for no manager. Only honored
    // when the caller has EMPLOYEE_MANAGE permission - see EmployeeService.
    private Long managerId;

    // Active/inactive toggle. Null means "leave as-is" (so existing callers
    // that don't send this field don't accidentally deactivate someone) -
    // only honored when the caller has EMPLOYEE_MANAGE, same as role/manager.
    private Boolean isActive;
}
