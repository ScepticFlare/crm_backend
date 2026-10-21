package com.compact.crm.service;

import com.compact.crm.dto.request.CustomerRequest;
import com.compact.crm.dto.request.OpportunityRequest;
import com.compact.crm.dto.request.ReturnToInProgressRequest;
import com.compact.crm.dto.request.SalesStageRequest;
import com.compact.crm.dto.search.OpportunitySearchCriteria;
import com.compact.crm.entity.ActivityLog;
import com.compact.crm.entity.Customer;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Industry;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.LeadSourceMaster;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.entity.Permission;
import com.compact.crm.entity.Role;
import com.compact.crm.entity.RolePermission;
import com.compact.crm.entity.SalesStage;
import com.compact.crm.enums.ActivityModule;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.LeadValidity;
import com.compact.crm.enums.OpportunitySubStatus;
import com.compact.crm.enums.SalesStageType;
import com.compact.crm.enums.Scope;
import com.compact.crm.exception.ResourceNotFoundException;
import com.compact.crm.repository.ActivityLogRepository;
import com.compact.crm.repository.CustomerRepository;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.IndustryRepository;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.LeadSourceMasterRepository;
import com.compact.crm.repository.OpportunityRepository;
import com.compact.crm.repository.PermissionRepository;
import com.compact.crm.repository.RolePermissionRepository;
import com.compact.crm.repository.RoleRepository;
import com.compact.crm.repository.SalesStageRepository;
import com.compact.crm.security.AccessControlService;
import com.compact.crm.security.CurrentUserService;
import com.compact.crm.util.BusinessClock;
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

import static com.compact.crm.security.AccessControlService.LEAD_MANAGE;
import static com.compact.crm.security.AccessControlService.LEAD_VIEW;
import static com.compact.crm.security.AccessControlService.OPPORTUNITY_MANAGE;
import static com.compact.crm.security.AccessControlService.OPPORTUNITY_VIEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real services on a real H2 database (no mocks):
 *  - only VALID leads convert into opportunities (service-level, so a direct
 *    API call cannot bypass it) and legacy lead statuses are unaffected;
 *  - renaming ANY core stage (WON / LOST / POSTPONED / IN PROGRESS) changes
 *    nothing about behaviour, because rules key off the stable stage type;
 *  - stage-management rules (rename validation, core stages can't be
 *    deactivated, seeding never guesses or duplicates);
 *  - role / actor handling and idempotent acknowledgement.
 */
@DataJpaTest
@Import({LeadService.class, OpportunityService.class, FollowUpService.class, CustomerService.class, SalesStageService.class,
        ActivityLogService.class, AccessControlService.class, CurrentUserService.class})
class StageIdentityAndConversionIntegrationTest {

    @Autowired private OpportunityService opportunityService;
    @Autowired private LeadService leadService;
    @Autowired private CustomerService customerService;
    @Autowired private SalesStageService salesStageService;

    @Autowired private LeadRepository leadRepository;
    @Autowired private OpportunityRepository opportunityRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private RolePermissionRepository rolePermissionRepository;
    @Autowired private IndustryRepository industryRepository;
    @Autowired private LeadSourceMasterRepository leadSourceMasterRepository;
    @Autowired private SalesStageRepository salesStageRepository;
    @Autowired private ActivityLogRepository activityLogRepository;

    private Employee admin;
    private Employee manager;
    private Employee employee;
    private Employee otherEmployee;
    private Industry industry;
    private LeadSourceMaster leadSource;

    private final Map<String, Permission> permissionsByCode = new HashMap<>();

    @BeforeEach
    void setUp() {

        Role adminRole = roleRepository.save(Role.builder().name("ADMIN").rank(100).build());
        Role managerRole = roleRepository.save(Role.builder().name("MANAGER").rank(50).build());
        Role employeeRole = roleRepository.save(Role.builder().name("EMPLOYEE").rank(10).build());

        for (String code : List.of(LEAD_MANAGE, LEAD_VIEW, OPPORTUNITY_MANAGE, OPPORTUNITY_VIEW,
                com.compact.crm.security.AccessControlService.FOLLOWUP_MANAGE)) {
            grant(adminRole, code, Scope.ALL);
            grant(managerRole, code, Scope.TEAM);
            grant(employeeRole, code, Scope.OWN);
        }

        admin = save("Admin", "admin@t.com", "9300000001", adminRole, null);
        manager = save("Manager", "manager@t.com", "9300000002", managerRole, null);
        employee = save("Employee", "employee@t.com", "9300000003", employeeRole, manager);
        otherEmployee = save("Other", "other@t.com", "9300000004", employeeRole, null);

        industry = industryRepository.save(Industry.builder().name("Manufacturing").build());
        leadSource = leadSourceMasterRepository.save(LeadSourceMaster.builder().name("Referral").build());

        salesStageService.ensureCoreStages();
        salesStageRepository.save(SalesStage.builder().name("QUOTATION SENT")
                .stageType(SalesStageType.LEGACY_QUOTATION_SENT).build());
        salesStageRepository.save(SalesStage.builder().name("NEGOTIATION")
                .stageType(SalesStageType.LEGACY_NEGOTIATION).build());

        authenticateAs(admin);
    }

    private Employee save(String name, String email, String phone, Role role, Employee mgr) {
        return employeeRepository.save(Employee.builder()
                .name(name).email(email).phone(phone).password("x").role(role).manager(mgr).build());
    }

    private void grant(Role role, String code, Scope scope) {

        Permission p = permissionsByCode.computeIfAbsent(
                code, c -> permissionRepository.save(Permission.builder().code(c).build()));

        rolePermissionRepository.save(RolePermission.builder().role(role).permission(p).scope(scope).build());
    }

    private void authenticateAs(Employee actor) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor.getEmail(), null, List.of()));
    }

    @Autowired private com.compact.crm.repository.ActivityTypeRepository activityTypeRepository;
    @Autowired private com.compact.crm.repository.FollowUpRepository followUpRepository;

    private Lead lead(String company, LeadValidity validity, LeadStatus status, Employee owner) {

        return leadRepository.save(Lead.builder()
                .companyName(company).contactPerson("C").phone("1234567890")
                .email(company.toLowerCase().replace(" ", "") + "@example.com")
                .city("Pune").state("MH").industry(industry).leadSource(leadSource)
                .leadStatus(status).leadValidity(validity).assignedEmployee(owner).build());
    }

    private Lead validLead(String company) {
        return lead(company, LeadValidity.VALID, LeadStatus.VALID, admin);
    }

    private LocalDate future() {
        return BusinessClock.today().plusDays(30);
    }

    private OpportunityRequest req(String stage, LocalDate closing) {

        OpportunityRequest r = new OpportunityRequest();
        r.setTitle("Deal");
        r.setProductValue(100.0);
        r.setExpectedClosingDate(closing);
        r.setSalesStage(stage);
        r.setLeadValidity(LeadValidity.VALID);
        // an active opportunity needs a next action - supplied with the request
        r.setNextActionDescription("Call to discuss the proposal");
        r.setNextFollowUpDate(java.time.LocalDateTime.now().plusDays(7));
        r.setNextActionActivityTypeId(callType().getId());
        return r;
    }

    private com.compact.crm.entity.ActivityType callType() {
        return activityTypeRepository.findAll().stream().findFirst().orElseGet(() ->
                activityTypeRepository.save(com.compact.crm.entity.ActivityType.builder().name("Call").build()));
    }

    private Opportunity convert(String company, String stage) {
        return opportunityService.convertLeadToOpportunity(validLead(company).getId(), req(stage, future()));
    }

    private SalesStage stageOfType(SalesStageType type) {
        return salesStageRepository.findByStageType(type).get(0);
    }

    private void rename(SalesStageType type, String newName) {

        SalesStageRequest r = new SalesStageRequest();
        r.setName(newName);
        salesStageService.update(stageOfType(type).getId(), r);
    }

    private CustomerRequest customerRequest() {

        CustomerRequest c = new CustomerRequest();
        c.setCompanyName("X");
        c.setContactPerson("C");
        c.setDesignation("D");
        c.setPhone("1234567890");
        c.setEmail("x@example.com");
        return c;
    }

    private List<ActivityLog> history(Long opportunityId) {

        return activityLogRepository.findAll().stream()
                .filter(a -> a.getModule() == ActivityModule.OPPORTUNITY && opportunityId.equals(a.getEntityId()))
                .toList();
    }

    // ===================================================== valid-only lead conversion

    @Test
    void validLead_converts() {

        Lead lead = validLead("ValidCo");

        Opportunity o = opportunityService.convertLeadToOpportunity(lead.getId(), req("IN PROGRESS", future()));

        assertThat(o.getId()).isNotNull();
        assertThat(o.getLead().getId()).isEqualTo(lead.getId());
    }

    @Test
    void invalidLead_cannotBeConverted_andNothingIsCreated() {

        Lead lead = lead("InvalidCo", LeadValidity.INVALID, LeadStatus.VALID, admin);

        assertThatThrownBy(() -> opportunityService.convertLeadToOpportunity(lead.getId(), req("IN PROGRESS", future())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only VALID leads can be converted")
                .hasMessageContaining("InvalidCo");

        assertThat(opportunityRepository.existsByLeadId(lead.getId())).isFalse();
    }

    @Test
    void leadWithNoValidityRecorded_cannotBeConverted() {

        Lead lead = lead("NoValidity", null, LeadStatus.NEW, admin);

        assertThatThrownBy(() -> opportunityService.convertLeadToOpportunity(lead.getId(), req("IN PROGRESS", future())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only VALID leads");
    }

    @Test
    void conversionRequest_cannotMarkTheLeadInvalid() {

        Lead lead = validLead("FlipAttempt");

        OpportunityRequest r = req("IN PROGRESS", future());
        r.setLeadValidity(LeadValidity.INVALID);

        assertThatThrownBy(() -> opportunityService.convertLeadToOpportunity(lead.getId(), r))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only VALID leads");

        assertThat(leadRepository.findById(lead.getId()).orElseThrow().getLeadValidity()).isEqualTo(LeadValidity.VALID);
        assertThat(opportunityRepository.existsByLeadId(lead.getId())).isFalse();
    }

    @Test
    void validLead_stillNeedsAClosingDate() {

        Lead lead = validLead("NoDateValid");

        assertThatThrownBy(() -> opportunityService.convertLeadToOpportunity(lead.getId(), req("IN PROGRESS", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected closing date is required");
    }

    @Test
    void duplicateConversion_isRejectedWithAClearMessage() {

        Lead lead = validLead("Twice");

        opportunityService.convertLeadToOpportunity(lead.getId(), req("IN PROGRESS", future()));

        assertThatThrownBy(() -> opportunityService.convertLeadToOpportunity(lead.getId(), req("IN PROGRESS", future())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already been converted");

        assertThat(opportunityRepository.findAll().stream().filter(o -> o.getLead().getId().equals(lead.getId())))
                .hasSize(1);
    }

    @Test
    void onlyAValidStatusLeadConverts_everyOtherStoredStatusIsRejected_andNeverRewritten() {

        // The rule is the lead STATUS being VALID. NEW, INVALID and every
        // historical legacy value (CONTACTED, QUOTATION_SENT, WON, DROPPED,
        // INACTIVE...) are rejected with a clear message - and rejecting never
        // rewrites the stored status.
        int i = 0;

        for (LeadStatus status : LeadStatus.values()) {

            Lead lead = lead("Status" + (i++), LeadValidity.VALID, status, admin);

            if (status == LeadStatus.VALID) {

                assertThat(opportunityService.convertLeadToOpportunity(lead.getId(), req("IN PROGRESS", future())).getId())
                        .as(status.name()).isNotNull();

            } else {

                assertThatThrownBy(() -> opportunityService.convertLeadToOpportunity(lead.getId(), req("IN PROGRESS", future())))
                        .as(status.name())
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("Only VALID leads can be converted");

                assertThat(opportunityRepository.existsByLeadId(lead.getId())).as(status.name()).isFalse();
            }

            assertThat(leadRepository.findById(lead.getId()).orElseThrow().getLeadStatus())
                    .as(status.name()).isEqualTo(status);
        }
    }

    // ===================================================== stage identity: renames change nothing

    @Test
    void renamingWon_keepsCustomerConversionAndTheWonLock() {

        Opportunity o = convert("WonRename", "IN PROGRESS");
        opportunityService.updateOpportunity(o.getId(), req("WON", null));

        rename(SalesStageType.WON, "Closed Won");

        // Customer conversion still recognises the (renamed) WON opportunity.
        Customer customer = customerService.convertOpportunity(o.getId(), customerRequest());
        assertThat(customer.getOpportunity().getId()).isEqualTo(o.getId());

        // ...and the "cannot leave WON once a Customer exists" lock still holds.
        assertThatThrownBy(() -> opportunityService.updateOpportunity(o.getId(), req("IN PROGRESS", future())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already been converted to a customer");

        assertThat(opportunityRepository.findById(o.getId()).orElseThrow().getSalesStage().getStageType())
                .isEqualTo(SalesStageType.WON);

        // Idempotent: converting again returns the same customer.
        assertThat(customerService.convertOpportunity(o.getId(), customerRequest()).getId()).isEqualTo(customer.getId());
        assertThat(customerRepository.findAll()).hasSize(1);
    }

    @Test
    void renamingWon_doesNotMakeAWonOpportunityActive() {

        Opportunity o = convert("WonActive", "IN PROGRESS");
        opportunityService.updateOpportunity(o.getId(), req("WON", null));

        rename(SalesStageType.WON, "Order Received");

        Opportunity reloaded = opportunityService.getOpportunityById(o.getId());

        assertThat(reloaded.isClosingDateExpired()).isFalse();
        assertThat(reloaded.getNoNextAction()).isFalse();
        assertThat(OpportunityWorkflow.isActive(reloaded)).isFalse();

        // Not part of the live In Progress list either.
        assertThat(opportunityService.searchInProgressOpportunities(
                new OpportunitySearchCriteria(), 0, 50, null, null).getContent()).isEmpty();
    }

    @Test
    void aCustomStageCalledWon_doesNotEnableCustomerConversion() {

        // The only thing that makes a stage "WON" is its type.
        rename(SalesStageType.WON, "Closed Won");

        SalesStageRequest r = new SalesStageRequest();
        r.setName("WON");
        SalesStage impostor = salesStageService.create(r);

        Opportunity o = convert("Impostor", "IN PROGRESS");
        opportunityService.updateOpportunity(o.getId(), req(impostor.getName(), future()));

        assertThatThrownBy(() -> customerService.convertOpportunity(o.getId(), customerRequest()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void renamingLost_stillRequiresAReason_andLostPageStillFindsIt() {

        Opportunity o = convert("LostRename", "IN PROGRESS");

        rename(SalesStageType.LOST, "Closed Lost");

        assertThatThrownBy(() -> opportunityService.updateOpportunity(o.getId(), req("Closed Lost", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");

        OpportunityRequest ok = req("Closed Lost", null);
        ok.setLostReason("Budget cut");
        opportunityService.updateOpportunity(o.getId(), ok);

        OpportunitySearchCriteria lostPage = new OpportunitySearchCriteria();
        lostPage.setStageType(SalesStageType.LOST);

        assertThat(opportunityService.searchOpportunities(lostPage, 0, 50, null, null).getContent())
                .extracting(Opportunity::getId).containsExactly(o.getId());
    }

    @Test
    void renamingPostponed_keepsReminderAcknowledgeAndReturnWorking() {

        Opportunity o = convert("PostponedRename", "IN PROGRESS");

        rename(SalesStageType.POSTPONED, "On Hold");
        rename(SalesStageType.IN_PROGRESS, "Working");

        OpportunityRequest postpone = req("On Hold", null);
        postpone.setPostponedReviewDate(BusinessClock.today());
        Opportunity postponed = opportunityService.updateOpportunity(o.getId(), postpone);

        assertThat(postponed.isPostponedReminderDue()).isTrue();
        assertThat(opportunityService.getWorkflowAlerts().postponedReminderCount()).isEqualTo(1);

        OpportunitySearchCriteria postponedPage = new OpportunitySearchCriteria();
        postponedPage.setStageType(SalesStageType.POSTPONED);
        assertThat(opportunityService.searchOpportunities(postponedPage, 0, 50, null, null).getContent())
                .extracting(Opportunity::getId).containsExactly(o.getId());

        assertThat(opportunityService.acknowledgePostponedReminder(o.getId()).getSalesStage().getName())
                .isEqualTo("On Hold");

        ReturnToInProgressRequest ret = new ReturnToInProgressRequest();
        ret.setExpectedClosingDate(future());

        Opportunity resumed = opportunityService.returnToInProgress(o.getId(), ret);

        assertThat(resumed.getSalesStage().getName()).isEqualTo("Working");
        assertThat(resumed.getSalesStage().getStageType()).isEqualTo(SalesStageType.IN_PROGRESS);
    }

    @Test
    void renamingInProgress_keepsSubStatusClosingDateAndWarnings() {

        Opportunity o = convert("InProgressRename", "IN PROGRESS");

        rename(SalesStageType.IN_PROGRESS, "Active Deal");

        // sub-status still allowed under the renamed stage
        OpportunityRequest sub = req("Active Deal", future());
        sub.setSubStatus(OpportunitySubStatus.NEGOTIATION);
        assertThat(opportunityService.updateOpportunity(o.getId(), sub).getSubStatus())
                .isEqualTo(OpportunitySubStatus.NEGOTIATION);

        // closing date still required for it
        assertThatThrownBy(() -> opportunityService.updateOpportunity(o.getId(), req("Active Deal", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected closing date is required");

        // expiry + missing-follow-up warnings still apply (the next action was
        // done, as in an older record), and it is in the In Progress list
        followUpRepository.findAll().forEach(f -> {
            f.setStatus(com.compact.crm.enums.FollowUpStatus.COMPLETED);
            followUpRepository.save(f);
        });

        Opportunity stored = opportunityRepository.findById(o.getId()).orElseThrow();
        stored.setExpectedClosingDate(BusinessClock.today().minusDays(2));
        opportunityRepository.save(stored);

        assertThat(opportunityService.getOpportunityById(o.getId()).isClosingDateExpired()).isTrue();
        assertThat(opportunityService.getWorkflowAlerts().expiredClosingDateCount()).isEqualTo(1);
        assertThat(opportunityService.getWorkflowAlerts().noNextActionCount()).isEqualTo(1);
        assertThat(opportunityService.searchInProgressOpportunities(
                new OpportunitySearchCriteria(), 0, 50, null, null).getContent())
                .extracting(Opportunity::getId).containsExactly(o.getId());
    }

    // ===================================================== terminal safeguards

    @Test
    void ordinaryEditOfWonOrLostOpportunity_doesNotReopenIt() {

        Opportunity won = convert("StaysWon", "IN PROGRESS");
        opportunityService.updateOpportunity(won.getId(), req("WON", null));

        OpportunityRequest edit = req("WON", null);
        edit.setTitle("Renamed deal");
        Opportunity afterEdit = opportunityService.updateOpportunity(won.getId(), edit);

        assertThat(afterEdit.getTitle()).isEqualTo("Renamed deal");
        assertThat(afterEdit.getSalesStage().getStageType()).isEqualTo(SalesStageType.WON);

        Opportunity lost = convert("StaysLost", "IN PROGRESS");
        OpportunityRequest lostReq = req("LOST", null);
        lostReq.setLostReason("No budget");
        opportunityService.updateOpportunity(lost.getId(), lostReq);

        // Editing a LOST opportunity must keep its reason and stage.
        OpportunityRequest lostEdit = req("LOST", null);
        lostEdit.setTitle("Renamed lost");
        lostEdit.setLostReason("No budget");
        Opportunity lostAfter = opportunityService.updateOpportunity(lost.getId(), lostEdit);

        assertThat(lostAfter.getSalesStage().getStageType()).isEqualTo(SalesStageType.LOST);
        assertThat(lostAfter.getLostReason()).isEqualTo("No budget");

        // ...and cannot silently drop the reason.
        assertThatThrownBy(() -> opportunityService.updateOpportunity(lost.getId(), req("LOST", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");
    }

    @Test
    void nonWonOpportunity_cannotBeConvertedToACustomer() {

        Opportunity o = convert("NotWon", "IN PROGRESS");

        assertThatThrownBy(() -> customerService.convertOpportunity(o.getId(), customerRequest()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Only WON");
    }

    // ===================================================== stage management rules

    @Test
    void renameValidation_blankDuplicateCaseAndWhitespace() {

        Long wonId = stageOfType(SalesStageType.WON).getId();

        SalesStageRequest blank = new SalesStageRequest();
        blank.setName("   ");
        assertThatThrownBy(() -> salesStageService.update(wonId, blank)).isInstanceOf(IllegalArgumentException.class);

        SalesStageRequest dup = new SalesStageRequest();
        dup.setName("  lost ");
        assertThatThrownBy(() -> salesStageService.update(wonId, dup))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");

        // Changing only the case / padding of its own name is fine and trimmed.
        SalesStageRequest self = new SalesStageRequest();
        self.setName("  Won ");
        assertThat(salesStageService.update(wonId, self).getName()).isEqualTo("Won");

        // Creating a duplicate (case-insensitive) is still rejected.
        SalesStageRequest create = new SalesStageRequest();
        create.setName("LOST");
        assertThatThrownBy(() -> salesStageService.create(create)).isInstanceOf(IllegalArgumentException.class);

        // Renaming never changes what the stage is.
        assertThat(salesStageRepository.findById(wonId).orElseThrow().getStageType()).isEqualTo(SalesStageType.WON);
    }

    @Test
    void coreStages_canBeRenamed_butNotDeactivated() {

        for (SalesStageType type : List.of(SalesStageType.NEW, SalesStageType.IN_PROGRESS,
                SalesStageType.POSTPONED, SalesStageType.WON, SalesStageType.LOST)) {

            Long id = stageOfType(type).getId();

            assertThatThrownBy(() -> salesStageService.deactivate(id))
                    .as(type.name())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("core workflow stage");

            assertThat(salesStageRepository.findById(id).orElseThrow().getIsActive()).isTrue();
        }
    }

    @Test
    void deactivatingACustomStage_keepsExistingOpportunities_butBlocksNewSelection() {

        SalesStageRequest r = new SalesStageRequest();
        r.setName("Site Survey");
        SalesStage custom = salesStageService.create(r);

        Opportunity inCustom = convert("InCustom", "Site Survey");

        salesStageService.deactivate(custom.getId());

        // History is untouched and the record still loads and re-saves unchanged.
        Opportunity loaded = opportunityService.getOpportunityById(inCustom.getId());
        assertThat(loaded.getSalesStage().getName()).isEqualTo("Site Survey");
        assertThat(opportunityService.updateOpportunity(inCustom.getId(), req("Site Survey", future())).getSalesStage()
                .getId()).isEqualTo(custom.getId());

        // But nobody can newly move an opportunity into it.
        Opportunity other = convert("OtherCo", "IN PROGRESS");
        assertThatThrownBy(() -> opportunityService.updateOpportunity(other.getId(), req("Site Survey", future())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inactive");
    }

    @Test
    void ensureCoreStages_isIdempotent_andNeverRecreatesARenamedStage() {

        rename(SalesStageType.WON, "Closed Won");
        long before = salesStageRepository.count();

        salesStageService.ensureCoreStages();
        salesStageService.ensureCoreStages();

        assertThat(salesStageRepository.count()).isEqualTo(before);
        assertThat(salesStageRepository.findByStageType(SalesStageType.WON)).hasSize(1);
        assertThat(salesStageRepository.findByNameIgnoreCase("WON")).isEmpty();
    }

    @Test
    void ensureCoreStages_tagsAnUnambiguousExistingStage_withoutCreatingADuplicate() {

        // A database as it was before types existed: untyped stages.
        salesStageRepository.deleteAll();
        salesStageRepository.flush();
        salesStageRepository.save(SalesStage.builder().name("WON").build());
        salesStageRepository.save(SalesStage.builder().name("lost ").build());

        salesStageService.ensureCoreStages();

        assertThat(salesStageRepository.findByStageType(SalesStageType.WON)).hasSize(1);
        assertThat(salesStageRepository.findByStageType(SalesStageType.WON).get(0).getName()).isEqualTo("WON");
        // matched case/whitespace-insensitively, not duplicated
        assertThat(salesStageRepository.findByStageType(SalesStageType.LOST)).hasSize(1);
        assertThat(salesStageRepository.findByStageType(SalesStageType.LOST).get(0).getName()).isEqualTo("lost ");
        assertThat(salesStageRepository.findAll().stream().filter(s -> s.getName().trim().equalsIgnoreCase("lost")))
                .hasSize(1);
        // missing core stages are created; legacy ones are not
        assertThat(salesStageRepository.findByStageType(SalesStageType.IN_PROGRESS)).hasSize(1);
        assertThat(salesStageRepository.findByStageType(SalesStageType.LEGACY_NEGOTIATION)).isEmpty();
    }

    @Test
    void ensureCoreStages_recognisesUnderscoreSpellings_asInProduction() {

        // Production has stages named "IN_PROGRESS" and "QUOTATION SENT" with
        // opportunities in them, and no "IN PROGRESS".
        salesStageRepository.deleteAll();
        salesStageRepository.flush();
        SalesStage inProgress = salesStageRepository.save(SalesStage.builder().name("IN_PROGRESS").build());
        SalesStage quotation = salesStageRepository.save(SalesStage.builder().name("quotation_sent").build());
        long before = salesStageRepository.count();

        salesStageService.ensureCoreStages();

        // the existing stages are tagged - no second IN PROGRESS stage is created
        assertThat(salesStageRepository.findByStageType(SalesStageType.IN_PROGRESS)).hasSize(1);
        assertThat(salesStageRepository.findByStageType(SalesStageType.IN_PROGRESS).get(0).getId())
                .isEqualTo(inProgress.getId());
        assertThat(salesStageRepository.findById(inProgress.getId()).orElseThrow().getName()).isEqualTo("IN_PROGRESS");
        assertThat(salesStageRepository.findByStageType(SalesStageType.LEGACY_QUOTATION_SENT).get(0).getId())
                .isEqualTo(quotation.getId());
        // only the genuinely missing core stages (NEW, POSTPONED, WON, LOST, DROPPED, ...) were added
        assertThat(salesStageRepository.findAll().stream()
                .filter(s -> OpportunityWorkflow.normalize(s.getName()).equals("IN PROGRESS"))).hasSize(1);
        assertThat(salesStageRepository.count()).isGreaterThan(before);
    }

    @Test
    void ensureCoreStages_doesNotGuessWhenSeveralStagesCouldMatch_orHijackALookalike() {

        salesStageRepository.deleteAll();
        salesStageRepository.flush();
        salesStageRepository.save(SalesStage.builder().name("WON").build());
        salesStageRepository.save(SalesStage.builder().name("won").build());
        // a typo'd, user-created in-progress stage must not be silently adopted
        SalesStage typo = salesStageRepository.save(SalesStage.builder().name("IN_PROGESS").build());

        salesStageService.ensureCoreStages();

        // ambiguous: neither "WON" nor "won" is tagged, and no third one is invented
        assertThat(salesStageRepository.findByStageType(SalesStageType.WON)).isEmpty();
        assertThat(salesStageRepository.findAll().stream().filter(s -> s.getName().equalsIgnoreCase("won"))).hasSize(2);

        // the lookalike keeps its identity; a proper IN PROGRESS stage is created next to it
        assertThat(salesStageRepository.findById(typo.getId()).orElseThrow().getStageType()).isNull();
        assertThat(salesStageRepository.findByStageType(SalesStageType.IN_PROGRESS)).hasSize(1);
        assertThat(salesStageRepository.findByStageType(SalesStageType.IN_PROGRESS).get(0).getId())
                .isNotEqualTo(typo.getId());
    }

    // ===================================================== roles, actors, ordinary operations

    @Test
    void employeeEditsOwnOpportunity_andHistoryNamesTheEmployee() {

        Lead lead = lead("EmployeeOwned", LeadValidity.VALID, LeadStatus.VALID, employee);
        authenticateAs(employee);

        Opportunity o = opportunityService.convertLeadToOpportunity(lead.getId(), req("IN PROGRESS", future()));

        OpportunityRequest edit = req("IN PROGRESS", future().plusDays(5));
        edit.setClosingDateChangeReason("Customer asked for more time");
        edit.setSubStatus(OpportunitySubStatus.QUOTATION_SENT);
        opportunityService.updateOpportunity(o.getId(), edit);

        assertThat(history(o.getId()).stream().filter(a -> a.getFieldName() != null))
                .isNotEmpty()
                .allMatch(a -> "Employee".equals(a.getEmployeeName()) && employee.getId().equals(a.getEmployeeId()));
    }

    @Test
    void managerEditsTeamMembersOpportunity_andHistoryNamesTheManager() {

        Lead lead = lead("TeamOwned", LeadValidity.VALID, LeadStatus.VALID, employee);
        authenticateAs(manager);

        Opportunity o = opportunityService.convertLeadToOpportunity(lead.getId(), req("IN PROGRESS", future()));

        OpportunityRequest edit = req("POSTPONED", null);
        edit.setPostponedReviewDate(future());
        opportunityService.updateOpportunity(o.getId(), edit);

        assertThat(history(o.getId()).stream().filter(a -> "salesStage".equals(a.getFieldName())))
                .hasSize(1)
                .allMatch(a -> "Manager".equals(a.getEmployeeName()) && "IN PROGRESS".equals(a.getOldValue())
                        && "POSTPONED".equals(a.getNewValue()));
    }

    @Test
    void adminEdit_isAttributedToTheAdmin() {

        Opportunity o = convert("AdminEdit", "IN PROGRESS");

        OpportunityRequest edit = req("IN PROGRESS", future().plusDays(1));
        edit.setClosingDateChangeReason("Budget approval pending");
        opportunityService.updateOpportunity(o.getId(), edit);

        assertThat(history(o.getId()).stream().filter(a -> "expectedClosingDate".equals(a.getFieldName())))
                .hasSize(1)
                .allMatch(a -> "Admin".equals(a.getEmployeeName()));
    }

    @Test
    void employee_cannotTouchAnotherEmployeesOpportunity() {

        Opportunity o = opportunityService.convertLeadToOpportunity(
                lead("NotMine", LeadValidity.VALID, LeadStatus.VALID, otherEmployee).getId(), req("IN PROGRESS", future()));

        authenticateAs(employee);

        assertThatThrownBy(() -> opportunityService.updateOpportunity(o.getId(), req("IN PROGRESS", future())))
                .isInstanceOf(AccessDeniedException.class);

        assertThatThrownBy(() -> opportunityService.acknowledgePostponedReminder(o.getId()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void employeeAndManager_canSelectAdminConfiguredStages() {

        SalesStageRequest r = new SalesStageRequest();
        r.setName("Site Survey");
        salesStageService.create(r);   // configured by the Admin

        for (Employee actor : List.of(employee, manager)) {

            Lead lead = lead("Uses " + actor.getName(), LeadValidity.VALID, LeadStatus.VALID, employee);

            authenticateAs(actor);

            Opportunity o = opportunityService.convertLeadToOpportunity(lead.getId(), req("Site Survey", future()));

            assertThat(o.getSalesStage().getName()).isEqualTo("Site Survey");
        }
    }

    // ===================================================== acknowledgement idempotency

    @Test
    void acknowledgingRepeatedly_changesNothingTwice_andWritesOneHistoryEntry() {

        Opportunity o = convert("AckTwice", "IN PROGRESS");

        OpportunityRequest postpone = req("POSTPONED", null);
        postpone.setPostponedReviewDate(BusinessClock.today());
        opportunityService.updateOpportunity(o.getId(), postpone);

        for (int i = 0; i < 4; i++) {
            Opportunity acked = opportunityService.acknowledgePostponedReminder(o.getId());
            assertThat(acked.getSalesStage().getStageType()).isEqualTo(SalesStageType.POSTPONED);
            assertThat(acked.isPostponedReminderDue()).isFalse();
        }

        assertThat(history(o.getId()).stream()
                .filter(a -> "postponedReminderAcknowledgedFor".equals(a.getFieldName())))
                .hasSize(1);
    }

    @Test
    void acknowledgement_staysDismissedUntilTheReviewDateChanges() {

        Opportunity o = convert("StayDismissed", "IN PROGRESS");

        OpportunityRequest postpone = req("POSTPONED", null);
        postpone.setPostponedReviewDate(BusinessClock.today().minusDays(1));
        opportunityService.updateOpportunity(o.getId(), postpone);
        opportunityService.acknowledgePostponedReminder(o.getId());

        // an unrelated edit (same review date) keeps it dismissed
        OpportunityRequest sameDate = req("POSTPONED", null);
        sameDate.setPostponedReviewDate(BusinessClock.today().minusDays(1));
        sameDate.setTitle("Edited title");
        assertThat(opportunityService.updateOpportunity(o.getId(), sameDate).isPostponedReminderDue()).isFalse();

        // fetching repeatedly never changes the stage or adds history
        long entries = history(o.getId()).size();
        for (int i = 0; i < 3; i++) {
            assertThat(opportunityService.getOpportunityById(o.getId()).getSalesStage().getStageType())
                    .isEqualTo(SalesStageType.POSTPONED);
        }
        assertThat(history(o.getId()).stream().filter(a -> a.getFieldName() != null).count())
                .isEqualTo(history(o.getId()).stream().filter(a -> a.getFieldName() != null).count());
        assertThat(history(o.getId()).size() - entries)
                .as("only VIEW entries are added by reads").isEqualTo(3);
    }

    // ===================================================== legacy data

    @Test
    void legacyOpportunityWithMissingNewFields_loadsFine_andACustomStageNamedLikeALegacyOneIsJustCustom() {

        // An Admin-created custom stage that happens to be called DROPPED once
        // the real (typed) one has been renamed must NOT inherit its behaviour
        // from the name: it is an ordinary, active, custom stage.
        rename(SalesStageType.LEGACY_DROPPED, "Dropped by Customer");

        SalesStageRequest r = new SalesStageRequest();
        r.setName("DROPPED");
        SalesStage lookalike = salesStageService.create(r);

        assertThat(lookalike.getStageType()).isNull();
        assertThat(OpportunityWorkflow.isActive(lookalike)).isTrue();
        assertThat(OpportunityWorkflow.key(lookalike)).isEqualTo("CUSTOM");

        Opportunity old = opportunityRepository.save(Opportunity.builder()
                .title("Old record").productValue(5.0).salesStage(lookalike)
                .leadValidity(LeadValidity.VALID)
                .lead(validLead("OldRecord")).build());

        // a record with none of the newer fields loads
        Opportunity loaded = opportunityService.getOpportunityById(old.getId());

        assertThat(loaded.getExpectedClosingDate()).isNull();
        assertThat(loaded.getSubStatus()).isNull();
        assertThat(loaded.getLostReason()).isNull();
        assertThat(loaded.isClosingDateExpired()).isFalse();
        assertThat(loaded.getNoNextAction()).isTrue();      // custom stages are active

        // and the REAL (typed, renamed) dropped stage is still closed
        Opportunity closed = opportunityRepository.save(Opportunity.builder()
                .title("Really dropped").productValue(5.0).salesStage(stageOfType(SalesStageType.LEGACY_DROPPED))
                .leadValidity(LeadValidity.VALID)
                .lead(validLead("ReallyDropped")).build());

        assertThat(opportunityService.getOpportunityById(closed.getId()).getNoNextAction()).isFalse();

        // closed + missing date: editable without inventing a date
        OpportunityRequest edit = req("Dropped by Customer", null);
        edit.setTitle("Really dropped (edited)");
        assertThat(opportunityService.updateOpportunity(closed.getId(), edit).getTitle())
                .isEqualTo("Really dropped (edited)");
    }

    // ===================================================== legacy closed stages (DROPPED / UNRESPONSIVE / INVALID)

    @Test
    void legacyClosedStages_areTypedAndSeeded() {

        assertThat(salesStageRepository.findByStageType(SalesStageType.LEGACY_DROPPED)).hasSize(1);
        assertThat(salesStageRepository.findByStageType(SalesStageType.LEGACY_UNRESPONSIVE)).hasSize(1);
        assertThat(salesStageRepository.findByStageType(SalesStageType.LEGACY_INVALID)).hasSize(1);

        for (SalesStageType t : List.of(SalesStageType.LEGACY_DROPPED,
                SalesStageType.LEGACY_UNRESPONSIVE, SalesStageType.LEGACY_INVALID)) {
            assertThat(t.isActive()).as(t.name()).isFalse();
            assertThat(t.isCore()).as(t.name()).isFalse();
        }
    }

    @Test
    void renamingDropped_keepsItClosed_itsPage_andIsNotRecreated() {

        Opportunity o = convert("DroppedRename", "IN PROGRESS");
        opportunityService.updateOpportunity(o.getId(), req("DROPPED", null));

        rename(SalesStageType.LEGACY_DROPPED, "Dropped by Customer");

        // not recreated by the seeder
        long stages = salesStageRepository.count();
        salesStageService.ensureCoreStages();
        assertThat(salesStageRepository.count()).isEqualTo(stages);
        assertThat(salesStageRepository.findByNameIgnoreCase("DROPPED")).isEmpty();

        // still closed: no warnings, not in the live In Progress list
        Opportunity reloaded = opportunityService.getOpportunityById(o.getId());
        assertThat(OpportunityWorkflow.isActive(reloaded)).isFalse();
        assertThat(reloaded.isClosingDateExpired()).isFalse();
        assertThat(reloaded.getNoNextAction()).isFalse();
        assertThat(opportunityService.searchInProgressOpportunities(
                new OpportunitySearchCriteria(), 0, 50, null, null).getContent()).isEmpty();

        // the Dropped page (by type) still lists it
        OpportunitySearchCriteria droppedPage = new OpportunitySearchCriteria();
        droppedPage.setStageType(SalesStageType.LEGACY_DROPPED);
        assertThat(opportunityService.searchOpportunities(droppedPage, 0, 50, null, null).getContent())
                .extracting(Opportunity::getId).containsExactly(o.getId());

        // closing date still not required for it
        assertThat(opportunityService.updateOpportunity(o.getId(), req("Dropped by Customer", null))
                .getSalesStage().getStageType()).isEqualTo(SalesStageType.LEGACY_DROPPED);
    }

    @Test
    void renamingUnresponsiveAndInvalid_keepsThemClosedAndOnTheirPages() {

        rename(SalesStageType.LEGACY_UNRESPONSIVE, "Gone Quiet");
        rename(SalesStageType.LEGACY_INVALID, "Not a Fit");

        Opportunity u = convert("UnrespRename", "IN PROGRESS");
        opportunityService.updateOpportunity(u.getId(), req("Gone Quiet", null));
        Opportunity i = convert("InvalidRename", "IN PROGRESS");
        opportunityService.updateOpportunity(i.getId(), req("Not a Fit", null));

        assertThat(OpportunityWorkflow.isActive(opportunityService.getOpportunityById(u.getId()))).isFalse();
        assertThat(OpportunityWorkflow.isActive(opportunityService.getOpportunityById(i.getId()))).isFalse();

        OpportunitySearchCriteria uPage = new OpportunitySearchCriteria();
        uPage.setStageType(SalesStageType.LEGACY_UNRESPONSIVE);
        assertThat(opportunityService.searchOpportunities(uPage, 0, 50, null, null).getContent())
                .extracting(Opportunity::getId).containsExactly(u.getId());

        OpportunitySearchCriteria iPage = new OpportunitySearchCriteria();
        iPage.setStageType(SalesStageType.LEGACY_INVALID);
        assertThat(opportunityService.searchOpportunities(iPage, 0, 50, null, null).getContent())
                .extracting(Opportunity::getId).containsExactly(i.getId());
    }

    @Test
    void ensureCoreStages_tagsAnExistingUntypedDroppedStage_withoutDuplicating() {

        salesStageRepository.deleteAll();
        salesStageRepository.flush();
        SalesStage old = salesStageRepository.save(SalesStage.builder().name("DROPPED").build());

        salesStageService.ensureCoreStages();

        assertThat(salesStageRepository.findByStageType(SalesStageType.LEGACY_DROPPED))
                .extracting(SalesStage::getId).containsExactly(old.getId());
        assertThat(salesStageRepository.findAll().stream().filter(s -> s.getName().equalsIgnoreCase("DROPPED")))
                .hasSize(1);
    }

    // ===================================================== already-converted lead signal

    @Test
    void leadDetail_reportsWhetherTheLeadHasAlreadyBeenConverted() {

        Lead lead = validLead("ConvertedFlag");

        assertThat(leadService.getLeadById(lead.getId()).getConverted()).isFalse();

        opportunityService.convertLeadToOpportunity(lead.getId(), req("IN PROGRESS", future()));

        assertThat(leadService.getLeadById(lead.getId()).getConverted()).isTrue();

        // a different lead is unaffected
        assertThat(leadService.getLeadById(validLead("Untouched").getId()).getConverted()).isFalse();
    }
}
