package com.compact.crm.service;

import com.compact.crm.dto.request.EmailTemplateRequest;
import com.compact.crm.entity.EmailTemplate;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Role;
import com.compact.crm.entity.RolePermission;
import com.compact.crm.enums.EmailTemplateType;
import com.compact.crm.enums.Scope;
import com.compact.crm.repository.EmailTemplateRepository;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.RolePermissionRepository;
import com.compact.crm.security.AccessControlService;
import com.compact.crm.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;

import static com.compact.crm.security.AccessControlService.EMAIL_TEMPLATE_MANAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailTemplateServiceTest {

    @Mock private EmailTemplateRepository emailTemplateRepository;
    @Mock private CurrentUserService currentUserService;
    @Mock private RolePermissionRepository rolePermissionRepository;
    @Mock private EmployeeRepository employeeRepository;

    private EmailTemplateService emailTemplateService;

    private Role adminRole;
    private Role employeeRole;
    private Employee admin;
    private Employee employee;

    @BeforeEach
    void setUp() {

        AccessControlService accessControlService =
                new AccessControlService(rolePermissionRepository, employeeRepository);

        emailTemplateService = new EmailTemplateService(
                emailTemplateRepository, accessControlService, currentUserService);

        adminRole = Role.builder().id(1L).name("ADMIN").rank(100).build();
        employeeRole = Role.builder().id(3L).name("EMPLOYEE").rank(10).build();

        admin = Employee.builder().id(1L).name("Admin").role(adminRole).build();
        employee = Employee.builder().id(2L).name("Employee").role(employeeRole).build();
    }

    private void grant(Role role, String permissionCode, Scope scope) {
        when(rolePermissionRepository.findByRole_IdAndPermission_Code(role.getId(), permissionCode))
                .thenReturn(Optional.of(RolePermission.builder().scope(scope).build()));
    }

    private void deny(Role role, String permissionCode) {
        when(rolePermissionRepository.findByRole_IdAndPermission_Code(role.getId(), permissionCode))
                .thenReturn(Optional.empty());
    }

    @Test
    void create_admin_savesTemplate() {

        grant(adminRole, EMAIL_TEMPLATE_MANAGE, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);
        when(emailTemplateRepository.save(any(EmailTemplate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        EmailTemplateRequest request = new EmailTemplateRequest(
                "Welcome", "Hi there", "Body text", EmailTemplateType.KEEP_IN_TOUCH, false);

        EmailTemplate saved = emailTemplateService.create(request);

        assertThat(saved.getName()).isEqualTo("Welcome");
        assertThat(saved.getCreatedByName()).isEqualTo("Admin");
        assertThat(saved.getIsDefault()).isFalse();
    }

    @Test
    void create_nonAdmin_rejected() {

        deny(employeeRole, EMAIL_TEMPLATE_MANAGE);
        when(currentUserService.getCurrentEmployee()).thenReturn(employee);

        EmailTemplateRequest request = new EmailTemplateRequest(
                "Welcome", "Hi", "Body", EmailTemplateType.KEEP_IN_TOUCH, false);

        assertThatThrownBy(() -> emailTemplateService.create(request))
                .isInstanceOf(AccessDeniedException.class);

        verify(emailTemplateRepository, never()).save(any());
    }

    @Test
    void create_asDefault_unsetsPreviousDefaultOfSameType() {

        grant(adminRole, EMAIL_TEMPLATE_MANAGE, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);

        EmailTemplate previousDefault = EmailTemplate.builder()
                .id(1L).type(EmailTemplateType.KEEP_IN_TOUCH).isDefault(true).build();

        when(emailTemplateRepository.findByTypeAndIsDefaultTrue(EmailTemplateType.KEEP_IN_TOUCH))
                .thenReturn(List.of(previousDefault));
        when(emailTemplateRepository.save(any(EmailTemplate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        EmailTemplateRequest request = new EmailTemplateRequest(
                "New Default", "Subject", "Body", EmailTemplateType.KEEP_IN_TOUCH, true);

        emailTemplateService.create(request);

        assertThat(previousDefault.getIsDefault()).isFalse();
        verify(emailTemplateRepository, times(2)).save(any(EmailTemplate.class));
    }

    @Test
    void deactivate_admin_setsInactiveAndClearsDefault() {

        grant(adminRole, EMAIL_TEMPLATE_MANAGE, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);

        EmailTemplate template = EmailTemplate.builder()
                .id(9L).isActive(true).isDefault(true).build();
        when(emailTemplateRepository.findById(9L)).thenReturn(Optional.of(template));
        when(emailTemplateRepository.save(any(EmailTemplate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        emailTemplateService.deactivate(9L);

        assertThat(template.getIsActive()).isFalse();
        assertThat(template.getIsDefault()).isFalse();
    }

    @Test
    void getDefaultForType_delegatesToRepository() {

        when(emailTemplateRepository.findByTypeAndIsDefaultTrueAndIsActiveTrue(EmailTemplateType.PRODUCT_BROCHURE))
                .thenReturn(Optional.empty());

        assertThat(emailTemplateService.getDefaultForType(EmailTemplateType.PRODUCT_BROCHURE)).isEmpty();
    }

}
