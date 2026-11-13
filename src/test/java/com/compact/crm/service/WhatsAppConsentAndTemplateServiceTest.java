package com.compact.crm.service;

import com.compact.crm.dto.request.WhatsAppBulkConsentRequest;
import com.compact.crm.dto.request.WhatsAppConsentRequest;
import com.compact.crm.dto.request.WhatsAppTemplatePreviewRequest;
import com.compact.crm.dto.request.WhatsAppTemplateRequest;
import com.compact.crm.dto.response.WhatsAppBulkConsentResult;
import com.compact.crm.dto.response.WhatsAppConsentResponse;
import com.compact.crm.dto.response.WhatsAppTemplatePreviewResponse;
import com.compact.crm.dto.response.WhatsAppTemplateResponse;
import com.compact.crm.entity.ActivityLog;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.Permission;
import com.compact.crm.entity.Role;
import com.compact.crm.entity.RolePermission;
import com.compact.crm.entity.WhatsAppConsentAudit;
import com.compact.crm.enums.ActivityAction;
import com.compact.crm.enums.BulkEmailTarget;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.Scope;
import com.compact.crm.enums.WhatsAppConsentStatus;
import com.compact.crm.exception.ResourceNotFoundException;
import com.compact.crm.repository.ActivityLogRepository;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.PermissionRepository;
import com.compact.crm.repository.RolePermissionRepository;
import com.compact.crm.repository.RoleRepository;
import com.compact.crm.repository.WhatsAppConsentAuditRepository;
import com.compact.crm.repository.WhatsAppConsentRepository;
import com.compact.crm.security.AccessControlService;
import com.compact.crm.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WhatsApp consent rules and CRM template management on a real H2 database.
 */
@DataJpaTest
@Import({WhatsAppConsentService.class, WhatsAppTemplateService.class, ActivityLogService.class,
        AccessControlService.class, CurrentUserService.class})
class WhatsAppConsentAndTemplateServiceTest {

    @Autowired private WhatsAppConsentService consent;
    @Autowired private WhatsAppTemplateService templates;

    @Autowired private LeadRepository leadRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private RolePermissionRepository rolePermissionRepository;
    @Autowired private WhatsAppConsentRepository consentRepository;
    @Autowired private WhatsAppConsentAuditRepository auditRepository;
    @Autowired private ActivityLogRepository activityLogRepository;

    private Employee admin;
    private Employee empA;
    private Employee empB;
    private Lead a1;
    private Lead a1Twin;   // same number as a1, different owner
    private Lead landline;

    private final Map<String, Permission> permissions = new HashMap<>();

    @BeforeEach
    void setUp() {

        Role adminRole = roleRepository.save(Role.builder().name("ADMIN").rank(100).build());
        Role employeeRole = roleRepository.save(Role.builder().name("EMPLOYEE").rank(10).build());

        for (String code : List.of(AccessControlService.LEAD_VIEW, AccessControlService.LEAD_MANAGE,
                AccessControlService.WHATSAPP_SEND)) {
            grant(adminRole, code, Scope.ALL);
            grant(employeeRole, code, Scope.OWN);
        }
        grant(adminRole, AccessControlService.WHATSAPP_CONSENT_MANAGE, Scope.ALL);
        grant(adminRole, AccessControlService.WHATSAPP_TEMPLATE_MANAGE, Scope.ALL);

        admin = employee("Admin", "adm@t.test", adminRole);
        empA = employee("Emp A", "ea@t.test", employeeRole);
        empB = employee("Emp B", "eb@t.test", employeeRole);

        a1 = lead("Acme", "9876500001", empA);
        a1Twin = lead("Acme Twin", "+91 98765 00001", empB);
        landline = lead("Landline", "04314021154", empA);

        authenticateAs(empA);
    }

    // ============================================================ consent

    @Test
    void nothingIsOptedInByDefault_aPhoneNumberIsNotConsent() {

        WhatsAppConsentResponse r = consent.getForLead(a1.getId());

        assertThat(r.validPhone()).isTrue();
        assertThat(r.status()).isNull();
        assertThat(r.phoneMasked()).isEqualTo("+91 ******0001");
        assertThat(r.phone()).isNull(); // full number only for consent admins
        assertThat(consentRepository.count()).isZero();
    }

    @Test
    void anOptInNeedsSourceDateAndEvidence_andIsAudited() {

        assertThatThrownBy(() -> consent.recordForLead(a1.getId(), req(WhatsAppConsentStatus.OPTED_IN, null, LocalDate.now(), "x")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("source");
        assertThatThrownBy(() -> consent.recordForLead(a1.getId(), req(WhatsAppConsentStatus.OPTED_IN, "Call", null, "x")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("date");
        assertThatThrownBy(() -> consent.recordForLead(a1.getId(), req(WhatsAppConsentStatus.OPTED_IN, "Call", LocalDate.now(), " ")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("evidence");
        assertThatThrownBy(() -> consent.recordForLead(a1.getId(),
                req(WhatsAppConsentStatus.OPTED_IN, "Call", com.compact.crm.util.BusinessClock.today().plusDays(1), "x")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("future");

        WhatsAppConsentResponse ok = consent.recordForLead(a1.getId(),
                req(WhatsAppConsentStatus.OPTED_IN, "phone call", LocalDate.now().minusDays(2), "Call note 14 Oct"));

        assertThat(ok.status()).isEqualTo(WhatsAppConsentStatus.OPTED_IN);
        assertThat(ok.source()).isEqualTo("PHONE_CALL");

        List<WhatsAppConsentAudit> audit = auditRepository.findAll();
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).getNewStatus()).isEqualTo(WhatsAppConsentStatus.OPTED_IN);
        assertThat(audit.get(0).getPerformedByName()).isEqualTo("Emp A");
        assertThat(audit.get(0).getLeadId()).isEqualTo(a1.getId());

        assertThat(activityLogRepository.findAll()).extracting(ActivityLog::getAction)
                .contains(ActivityAction.WHATSAPP_CONSENT_CHANGED);
    }

    @Test
    void employeesFollowRecordOwnership() {

        authenticateAs(empB);

        assertThatThrownBy(() -> consent.recordForLead(a1.getId(),
                req(WhatsAppConsentStatus.OPTED_IN, "call", LocalDate.now(), "x")))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> consent.getForLead(a1.getId())).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void consentIsPerNumber_soAnOptOutViaAnotherRecordBlocksThisOne_andOnlyAnAdminCanOverrideIt() {

        consent.recordForLead(a1.getId(), req(WhatsAppConsentStatus.OPTED_IN, "call", LocalDate.now().minusDays(10), "x"));

        // empB opts out through their own lead that shares the number
        authenticateAs(empB);
        consent.recordForLead(a1Twin.getId(), req(WhatsAppConsentStatus.OPTED_OUT, null, null, null));

        authenticateAs(empA);
        WhatsAppConsentResponse now = consent.getForLead(a1.getId());
        assertThat(now.status()).isEqualTo(WhatsAppConsentStatus.OPTED_OUT);
        assertThat(now.canRecordOptIn()).isFalse();

        // the owner cannot simply opt them back in
        assertThatThrownBy(() -> consent.recordForLead(a1.getId(),
                req(WhatsAppConsentStatus.OPTED_IN, "call", LocalDate.now(), "x")))
                .isInstanceOf(AccessDeniedException.class).hasMessageContaining("administrator");

        // an admin needs a consent dated AFTER the opt-out
        authenticateAs(admin);
        assertThatThrownBy(() -> consent.recordForLead(a1.getId(),
                req(WhatsAppConsentStatus.OPTED_IN, "call", LocalDate.now().minusDays(3), "x")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dated after");

        assertThat(consentRepository.findByPhoneE164("919876500001").orElseThrow().getStatus())
                .isEqualTo(WhatsAppConsentStatus.OPTED_OUT);
    }

    @Test
    void aRecordWithoutAUsableMobileNumberCannotHoldConsent() {

        assertThat(consent.getForLead(landline.getId()).validPhone()).isFalse();
        assertThatThrownBy(() -> consent.recordForLead(landline.getId(),
                req(WhatsAppConsentStatus.OPTED_IN, "call", LocalDate.now(), "x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bulkImport_isAdminOnly_needsDocumentation_andNeverOverridesAnOptOut() {

        WhatsAppBulkConsentRequest request = new WhatsAppBulkConsentRequest(
                List.of("9876500001", "9876500002", "+91 98765 00002", "04314021154", "9876500003"),
                "Website enquiry form", "Form submissions export 2026-10", LocalDate.now().minusDays(30));

        assertThatThrownBy(() -> consent.bulkOptIn(request)).isInstanceOf(AccessDeniedException.class);

        authenticateAs(admin);
        consent.recordForPhone(req(WhatsAppConsentStatus.OPTED_OUT, null, null, null, "9876500003"));

        WhatsAppBulkConsentResult result = consent.bulkOptIn(request);

        assertThat(result.optedIn()).isEqualTo(2);
        assertThat(result.duplicates()).isEqualTo(1);
        assertThat(result.invalid()).isEqualTo(1);
        assertThat(result.skippedOptedOut()).isEqualTo(1);
        assertThat(result.rows()).extracting(WhatsAppBulkConsentResult.Row::outcome).containsExactly(
                "OPTED_IN", "OPTED_IN", "DUPLICATE_IN_LIST", "INVALID_PHONE", "SKIPPED_OPTED_OUT");

        assertThat(consentRepository.findByPhoneE164("919876500003").orElseThrow().getStatus())
                .isEqualTo(WhatsAppConsentStatus.OPTED_OUT);

        // every imported number is audited under one import reference
        assertThat(auditRepository.findAll().stream()
                .filter(a -> result.importReference().equals(a.getImportReference())).count()).isEqualTo(2);

        // a second identical import changes nothing
        assertThat(consent.bulkOptIn(request).alreadyOptedIn()).isEqualTo(2);
    }

    @Test
    void anInboundStopOptsOut_idempotently() {

        authenticateAs(admin);
        consent.recordForPhone(req(WhatsAppConsentStatus.OPTED_IN, "call", LocalDate.now().minusDays(1), "x", "9876500001"));

        assertThat(consent.optOutFromInbound("919876500001", "reply")).isTrue();
        assertThat(consent.optOutFromInbound("919876500001", "reply")).isFalse();

        assertThat(consentRepository.findByPhoneE164("919876500001").orElseThrow().getSource())
                .isEqualTo(WhatsAppConsentService.SOURCE_INBOUND_STOP);
        assertThat(auditRepository.findByPhoneE164OrderByPerformedAtDescIdDesc("919876500001")).hasSize(2);
    }

    @Test
    void consentAdministrationIsAdminOnly() {

        assertThatThrownBy(() -> consent.list(null, 0, 10)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> consent.audit(0, 10)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> consent.recordForPhone(req(WhatsAppConsentStatus.OPTED_OUT, null, null, null, "9876500001")))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ============================================================ templates

    @Test
    void templatesAreAdminManaged_validated_andVersionedOnBodyChange() {

        WhatsAppTemplateRequest req = new WhatsAppTemplateRequest("Follow-up", "Post-enquiry",
                "Hi {{contactPerson}}, thanks for your interest in {{interestedProduct}}.");

        assertThatThrownBy(() -> templates.create(req)).isInstanceOf(AccessDeniedException.class);

        authenticateAs(admin);

        assertThatThrownBy(() -> templates.create(new WhatsAppTemplateRequest("Bad", null, "Hi {{nickname}}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsupported field");

        WhatsAppTemplateResponse created = templates.create(req);
        assertThat(created.version()).isEqualTo(1);
        assertThat(created.issues()).isEmpty();

        // renaming only: same version; changing the text: next version
        assertThat(templates.update(created.id(), new WhatsAppTemplateRequest("Renamed", null, req.getBody())).version())
                .isEqualTo(1);
        assertThat(templates.update(created.id(), new WhatsAppTemplateRequest("Renamed", null, "Hello {{companyName}}"))
                .version()).isEqualTo(2);

        templates.deactivate(created.id());

        authenticateAs(empA);
        assertThat(templates.listActive()).isEmpty();

        authenticateAs(admin);
        templates.activate(created.id());

        authenticateAs(empA);
        assertThat(templates.listActive()).extracting(WhatsAppTemplateResponse::name).containsExactly("Renamed");
        assertThatThrownBy(() -> templates.listAll()).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void preview_usesLabelledSampleData_orARealRecordTheAdminMayView() {

        authenticateAs(admin);

        WhatsAppTemplatePreviewResponse sample = templates.preview(
                new WhatsAppTemplatePreviewRequest("Hi {{contactPerson}} at {{companyName}} re {{opportunityTitle}}", null, null));

        assertThat(sample.sample()).isTrue();
        assertThat(sample.issues()).isEmpty();
        assertThat(sample.renderedText()).isEqualTo("Hi Rahul Sharma at ABC Hospitals re Hospital UPS upgrade");

        WhatsAppTemplatePreviewResponse real = templates.preview(
                new WhatsAppTemplatePreviewRequest("Hi {{companyName}} ({{designation}})", BulkEmailTarget.LEAD, a1.getId()));

        assertThat(real.sample()).isFalse();
        assertThat(real.renderedText()).isEqualTo("Hi Acme ({{designation}})");
        assertThat(real.issues()).containsExactly("Designation is missing for this recipient");

        assertThatThrownBy(() -> templates.preview(
                new WhatsAppTemplatePreviewRequest("x", BulkEmailTarget.LEAD, 999_999L)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ============================================================ helpers

    private WhatsAppConsentRequest req(WhatsAppConsentStatus status, String source, LocalDate date, String evidence) {
        return req(status, source, date, evidence, null);
    }

    private WhatsAppConsentRequest req(WhatsAppConsentStatus status, String source, LocalDate date, String evidence,
                                       String phone) {
        return new WhatsAppConsentRequest(status, source, evidence, date, phone);
    }

    private Employee employee(String name, String email, Role role) {
        return employeeRepository.save(Employee.builder()
                .name(name).email(email).phone("96" + String.format("%08d", employeeRepository.count() + 1))
                .password("x").role(role).build());
    }

    private Lead lead(String company, String phone, Employee owner) {
        return leadRepository.save(Lead.builder().companyName(company).contactPerson("C").phone(phone)
                .leadStatus(LeadStatus.VALID).assignedEmployee(owner).build());
    }

    private void grant(Role role, String code, Scope scope) {
        Permission p = permissions.computeIfAbsent(code, c -> permissionRepository.save(Permission.builder().code(c).build()));
        rolePermissionRepository.save(RolePermission.builder().role(role).permission(p).scope(scope).build());
    }

    private void authenticateAs(Employee actor) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor.getEmail(), null, List.of()));
    }
}
