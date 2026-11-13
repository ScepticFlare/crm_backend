package com.compact.crm.service;

import com.compact.crm.dto.request.WhatsAppConsentRequest;
import com.compact.crm.dto.response.WhatsAppConsentResponse;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Permission;
import com.compact.crm.entity.Role;
import com.compact.crm.entity.RolePermission;
import com.compact.crm.enums.Scope;
import com.compact.crm.enums.WhatsAppConsentStatus;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.PermissionRepository;
import com.compact.crm.repository.RolePermissionRepository;
import com.compact.crm.repository.RoleRepository;
import com.compact.crm.security.AccessControlService;
import com.compact.crm.security.CurrentUserService;
import com.compact.crm.util.BusinessClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.List;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The consent date's "not in the future" rule uses the business day (IST,
 * util.BusinessClock), the same day the browser offers as "today". The server
 * JVM runs on UTC on Render, which before 05:30 IST is still on the previous
 * calendar day - with the JVM clock, a consent recorded "today" then was
 * refused as a future date.
 */
@DataJpaTest
@Import({WhatsAppConsentService.class, ActivityLogService.class, AccessControlService.class, CurrentUserService.class})
class WhatsAppConsentBusinessDateTest {

    @Autowired private WhatsAppConsentService consent;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private RolePermissionRepository rolePermissionRepository;

    private TimeZone originalZone;

    @BeforeEach
    void setUp() {
        originalZone = TimeZone.getDefault();

        Role adminRole = roleRepository.save(Role.builder().name("ADMIN").rank(100).build());
        for (String code : List.of(AccessControlService.WHATSAPP_CONSENT_MANAGE, AccessControlService.LEAD_VIEW,
                AccessControlService.LEAD_MANAGE)) {
            Permission p = permissionRepository.save(Permission.builder().code(code).build());
            rolePermissionRepository.save(RolePermission.builder().role(adminRole).permission(p).scope(Scope.ALL).build());
        }
        Employee admin = employeeRepository.save(Employee.builder().name("Admin").email("adm@t.test")
                .phone("9600000001").password("x").role(adminRole).isActive(true).build());

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin.getEmail(), null, List.of()));
    }

    @AfterEach
    void restoreZone() {
        TimeZone.setDefault(originalZone);
        SecurityContextHolder.clearContext();
    }

    private WhatsAppConsentRequest optIn(LocalDate date, String phone) {
        return new WhatsAppConsentRequest(WhatsAppConsentStatus.OPTED_IN, "call", "Call note", date, phone);
    }

    @Test
    void aConsentDatedTodayInIndia_isAccepted_evenWhileTheServerClockIsStillOnTheDayBefore() {

        // A server zone 17h30 behind IST: for most of the Indian day its
        // LocalDate.now() is still yesterday, like UTC is before 05:30 IST.
        TimeZone.setDefault(TimeZone.getTimeZone("Etc/GMT+12"));
        LocalDate indianToday = BusinessClock.today();

        WhatsAppConsentResponse r = consent.recordForPhone(optIn(indianToday, "9876500001"));
        assertThat(r.status()).isEqualTo(WhatsAppConsentStatus.OPTED_IN);
        assertThat(r.consentedAt().toLocalDate()).isEqualTo(indianToday);   // stored exactly as chosen

        assertThatThrownBy(() -> consent.recordForPhone(optIn(indianToday.plusDays(1), "9876500002")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("future");
    }
}
