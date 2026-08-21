package com.compact.crm.service;

import com.compact.crm.dto.request.BulkKeepInTouchEmailRequest;
import com.compact.crm.dto.request.SendLeadEmailRequest;
import com.compact.crm.dto.response.BulkEmailEligibilityResponse;
import com.compact.crm.dto.response.BulkEmailResult;
import com.compact.crm.dto.response.EmailPreviewResponse;
import com.compact.crm.entity.Document;
import com.compact.crm.entity.EmailTemplate;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.LeadProduct;
import com.compact.crm.entity.Product;
import com.compact.crm.entity.Role;
import com.compact.crm.entity.RolePermission;
import com.compact.crm.enums.ActivityAction;
import com.compact.crm.enums.ActivityModule;
import com.compact.crm.enums.EmailTemplateType;
import com.compact.crm.enums.Scope;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.RolePermissionRepository;
import com.compact.crm.security.AccessControlService;
import com.compact.crm.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;

import static com.compact.crm.security.AccessControlService.EMAIL_SEND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LeadEmailServiceTest {

    @Mock private LeadRepository leadRepository;
    @Mock private CurrentUserService currentUserService;
    @Mock private RolePermissionRepository rolePermissionRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private EmailTemplateService emailTemplateService;
    @Mock private DocumentService documentService;
    @Mock private ActivityLogService activityLogService;
    @Mock private BrevoEmailClient brevoEmailClient;

    private LeadEmailService leadEmailService;

    private Role adminRole;
    private Role employeeRole;

    private Employee admin;
    private Employee employeeA;
    private Employee employeeB;

    @BeforeEach
    void setUp() {

        AccessControlService accessControlService =
                new AccessControlService(rolePermissionRepository, employeeRepository);

        leadEmailService = new LeadEmailService(
                leadRepository, accessControlService, currentUserService,
                emailTemplateService, documentService, activityLogService, brevoEmailClient
        );

        adminRole = Role.builder().id(1L).name("ADMIN").rank(100).build();
        employeeRole = Role.builder().id(3L).name("EMPLOYEE").rank(10).build();

        admin = Employee.builder().id(1L).name("Admin").role(adminRole).build();
        employeeA = Employee.builder().id(2L).name("Employee A").role(employeeRole).build();
        employeeB = Employee.builder().id(3L).name("Employee B").role(employeeRole).build();
    }

    private void grant(Role role, String permissionCode, Scope scope) {
        when(rolePermissionRepository.findByRole_IdAndPermission_Code(role.getId(), permissionCode))
                .thenReturn(Optional.of(RolePermission.builder().scope(scope).build()));
    }

    private Lead leadOwnedBy(Long id, Employee owner, String email) {
        return Lead.builder()
                .id(id)
                .companyName("ABC Industries")
                .contactPerson("Rajesh")
                .email(email)
                .assignedEmployee(owner)
                .leadProducts(List.of())
                .build();
    }

    @Test
    void sendIndividual_authorizedWithEmail_sendsAndLogsActivity() {

        grant(employeeRole, EMAIL_SEND, Scope.OWN);
        when(currentUserService.getCurrentEmployee()).thenReturn(employeeA);

        Lead lead = leadOwnedBy(10L, employeeA, "rajesh@acme.com");
        when(leadRepository.findById(10L)).thenReturn(Optional.of(lead));

        SendLeadEmailRequest request = new SendLeadEmailRequest(
                EmailTemplateType.PRODUCT_BROCHURE, null, "Subject", "Body", List.of());

        var response = leadEmailService.sendIndividual(10L, request);

        assertThat(response.isSent()).isTrue();
        assertThat(response.getRecipient()).isEqualTo("rajesh@acme.com");

        verify(brevoEmailClient).send(eq("rajesh@acme.com"), eq("Subject"), eq("Body"), any());
        verify(activityLogService).log(
                eq(employeeA), eq(ActivityModule.LEAD), eq(ActivityAction.EMAIL_SENT),
                eq(10L), eq("ABC Industries"), any());
    }

    // Multiple attachments: each selected Document's bytes (loaded through
    // DocumentService, which delegates to DocumentStorageService - local
    // disk or Supabase, this test doesn't care which) must reach
    // BrevoEmailClient as an EmailAttachment carrying that Document's
    // fileName and exact bytes, unmodified.
    @SuppressWarnings("unchecked")
    @Test
    void sendIndividual_withAttachments_passesDocumentBytesToBrevoClient() {

        grant(employeeRole, EMAIL_SEND, Scope.OWN);
        when(currentUserService.getCurrentEmployee()).thenReturn(employeeA);

        Lead lead = leadOwnedBy(13L, employeeA, "rajesh@acme.com");
        when(leadRepository.findById(13L)).thenReturn(Optional.of(lead));

        Document brochure = Document.builder().id(100L).fileName("brochure.pdf").build();
        Document profile = Document.builder().id(101L).fileName("company-profile.pdf").build();
        when(documentService.getById(100L)).thenReturn(brochure);
        when(documentService.getById(101L)).thenReturn(profile);
        when(documentService.loadBytes(brochure)).thenReturn("brochure-bytes".getBytes());
        when(documentService.loadBytes(profile)).thenReturn("profile-bytes".getBytes());

        SendLeadEmailRequest request = new SendLeadEmailRequest(
                EmailTemplateType.PRODUCT_BROCHURE, null, "Subject", "Body", List.of(100L, 101L));

        leadEmailService.sendIndividual(13L, request);

        ArgumentCaptor<List<EmailAttachment>> captor = ArgumentCaptor.forClass(List.class);
        verify(brevoEmailClient).send(eq("rajesh@acme.com"), eq("Subject"), eq("Body"), captor.capture());

        List<EmailAttachment> attachments = captor.getValue();
        assertThat(attachments).hasSize(2);
        assertThat(attachments.get(0).filename()).isEqualTo("brochure.pdf");
        assertThat(attachments.get(0).bytes()).isEqualTo("brochure-bytes".getBytes());
        assertThat(attachments.get(1).filename()).isEqualTo("company-profile.pdf");
        assertThat(attachments.get(1).bytes()).isEqualTo("profile-bytes".getBytes());
    }

    @Test
    void sendIndividual_leadWithoutEmail_rejectedBeforeSending() {

        grant(employeeRole, EMAIL_SEND, Scope.OWN);
        when(currentUserService.getCurrentEmployee()).thenReturn(employeeA);

        Lead lead = leadOwnedBy(11L, employeeA, null);
        when(leadRepository.findById(11L)).thenReturn(Optional.of(lead));

        SendLeadEmailRequest request = new SendLeadEmailRequest(
                EmailTemplateType.KEEP_IN_TOUCH, null, "Subject", "Body", List.of());

        assertThatThrownBy(() -> leadEmailService.sendIndividual(11L, request))
                .isInstanceOf(IllegalArgumentException.class);

        verify(brevoEmailClient, never()).send(any(), any(), any(), any());
        verify(activityLogService, never()).log(any(), any(), any(), anyLong(), any(), any());
    }

    @Test
    void sendIndividual_outsideOwnScope_rejected() {

        grant(employeeRole, EMAIL_SEND, Scope.OWN);
        when(currentUserService.getCurrentEmployee()).thenReturn(employeeA);

        Lead lead = leadOwnedBy(12L, employeeB, "other@acme.com");
        when(leadRepository.findById(12L)).thenReturn(Optional.of(lead));

        SendLeadEmailRequest request = new SendLeadEmailRequest(
                EmailTemplateType.KEEP_IN_TOUCH, null, "Subject", "Body", List.of());

        assertThatThrownBy(() -> leadEmailService.sendIndividual(12L, request))
                .isInstanceOf(AccessDeniedException.class);

        verify(brevoEmailClient, never()).send(any(), any(), any(), any());
    }

    @Test
    void previewIndividual_rendersTemplatePlaceholders_forThisLead() {

        grant(adminRole, EMAIL_SEND, Scope.ALL);
        when(currentUserService.getCurrentEmployee()).thenReturn(admin);

        Product product = Product.builder().id(5L).name("Inverter Battery").build();
        Lead lead = Lead.builder()
                .id(20L)
                .companyName("XYZ Corp")
                .contactPerson("Meena")
                .email("meena@xyz.com")
                .assignedEmployee(employeeA)
                .leadProducts(List.of(LeadProduct.builder().product(product).quantity(1).build()))
                .build();
        when(leadRepository.findById(20L)).thenReturn(Optional.of(lead));

        EmailTemplate template = EmailTemplate.builder()
                .id(7L)
                .subject("Hello {{contactPerson}}")
                .body("Dear {{contactPerson}} from {{companyName}}, re: {{interestedProduct}}")
                .type(EmailTemplateType.PRODUCT_BROCHURE)
                .build();
        when(emailTemplateService.getById(7L)).thenReturn(template);
        when(documentService.list(any(), any())).thenReturn(List.of());

        EmailPreviewResponse preview =
                leadEmailService.previewIndividual(20L, EmailTemplateType.PRODUCT_BROCHURE, 7L);

        assertThat(preview.getSubject()).isEqualTo("Hello Meena");
        assertThat(preview.getBody())
                .isEqualTo("Dear Meena from XYZ Corp, re: Inverter Battery");
    }

    @Test
    void bulkEligibility_splitsByAuthorizationAndEmailPresence() {

        grant(employeeRole, EMAIL_SEND, Scope.OWN);
        when(currentUserService.getCurrentEmployee()).thenReturn(employeeA);

        Lead ownedWithEmail = leadOwnedBy(30L, employeeA, "a@acme.com");
        Lead ownedNoEmail = leadOwnedBy(31L, employeeA, "");
        Lead notOwned = leadOwnedBy(32L, employeeB, "b@acme.com");

        when(leadRepository.findById(30L)).thenReturn(Optional.of(ownedWithEmail));
        when(leadRepository.findById(31L)).thenReturn(Optional.of(ownedNoEmail));
        when(leadRepository.findById(32L)).thenReturn(Optional.of(notOwned));
        when(leadRepository.findById(33L)).thenReturn(Optional.empty());

        BulkEmailEligibilityResponse result =
                leadEmailService.bulkEligibility(List.of(30L, 31L, 32L, 33L));

        assertThat(result.getEligibleIds()).containsExactly(30L);
        assertThat(result.getIneligibleIds()).containsExactlyInAnyOrder(31L, 32L, 33L);
        assertThat(result.getEligibleCount()).isEqualTo(1);
        assertThat(result.getIneligibleCount()).isEqualTo(3);
    }

    @Test
    void sendBulkKeepInTouch_skipsIneligible_sendsToEligible_withPerLeadPlaceholders() {

        grant(employeeRole, EMAIL_SEND, Scope.OWN);
        when(currentUserService.getCurrentEmployee()).thenReturn(employeeA);

        Lead eligible = leadOwnedBy(40L, employeeA, "eligible@acme.com");
        Lead noEmail = leadOwnedBy(41L, employeeA, null);
        Lead notOwned = leadOwnedBy(42L, employeeB, "other@acme.com");

        when(leadRepository.findById(40L)).thenReturn(Optional.of(eligible));
        when(leadRepository.findById(41L)).thenReturn(Optional.of(noEmail));
        when(leadRepository.findById(42L)).thenReturn(Optional.of(notOwned));

        BulkKeepInTouchEmailRequest request = new BulkKeepInTouchEmailRequest(
                List.of(40L, 41L, 42L), null,
                "Greetings from Compact Systems",
                "Dear {{contactPerson}}, ...",
                List.of()
        );

        BulkEmailResult result = leadEmailService.sendBulkKeepInTouch(request);

        assertThat(result.getSucceededIds()).containsExactly(40L);
        assertThat(result.getSkippedIds()).containsExactlyInAnyOrder(41L, 42L);
        assertThat(result.getSkipReasons()).containsEntry(41L, "No email address");
        assertThat(result.getSkipReasons()).containsEntry(42L, "Not authorized");

        verify(brevoEmailClient, org.mockito.Mockito.times(1))
                .send(eq("eligible@acme.com"), any(), any(), any());
        verify(activityLogService, org.mockito.Mockito.times(1)).log(
                eq(employeeA), eq(ActivityModule.LEAD), eq(ActivityAction.EMAIL_SENT),
                eq(40L), any(), any());
    }

    @Test
    void sendBulkKeepInTouch_mailSendFailure_skipsWithReason_continuesOtherRecipients() {

        grant(employeeRole, EMAIL_SEND, Scope.OWN);
        when(currentUserService.getCurrentEmployee()).thenReturn(employeeA);

        Lead failing = leadOwnedBy(50L, employeeA, "fail@acme.com");
        Lead succeeding = leadOwnedBy(51L, employeeA, "ok@acme.com");

        when(leadRepository.findById(50L)).thenReturn(Optional.of(failing));
        when(leadRepository.findById(51L)).thenReturn(Optional.of(succeeding));

        doThrow(new IllegalStateException("Unable to send the email. Please try again."))
                .doNothing()
                .when(brevoEmailClient).send(any(), any(), any(), any());

        BulkKeepInTouchEmailRequest request = new BulkKeepInTouchEmailRequest(
                List.of(50L, 51L), null, "Subject", "Body", List.of());

        BulkEmailResult result = leadEmailService.sendBulkKeepInTouch(request);

        assertThat(result.getSucceededIds()).containsExactly(51L);
        assertThat(result.getSkippedIds()).containsExactly(50L);
        assertThat(result.getSkipReasons()).containsEntry(50L, "Send failed");
    }

}
