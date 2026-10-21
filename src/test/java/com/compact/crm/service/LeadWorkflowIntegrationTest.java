package com.compact.crm.service;

import com.compact.crm.dto.request.LeadRequest;
import com.compact.crm.dto.request.OpportunityRequest;
import com.compact.crm.dto.response.DuplicateLeadsResponse;
import com.compact.crm.dto.search.LeadSearchCriteria;
import com.compact.crm.entity.ActivityLog;
import com.compact.crm.entity.ActivityType;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.FollowUp;
import com.compact.crm.entity.Industry;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.LeadSourceMaster;
import com.compact.crm.entity.Permission;
import com.compact.crm.entity.Role;
import com.compact.crm.entity.RolePermission;
import com.compact.crm.enums.ActivityModule;
import com.compact.crm.enums.FollowUpStatus;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.LeadValidity;
import com.compact.crm.enums.Scope;
import com.compact.crm.repository.ActivityLogRepository;
import com.compact.crm.repository.ActivityTypeRepository;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.FollowUpRepository;
import com.compact.crm.repository.IndustryRepository;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.LeadSourceMasterRepository;
import com.compact.crm.repository.PermissionRepository;
import com.compact.crm.repository.RolePermissionRepository;
import com.compact.crm.repository.RoleRepository;
import com.compact.crm.security.AccessControlService;
import com.compact.crm.security.CurrentUserService;
import com.compact.crm.util.BusinessClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.compact.crm.security.AccessControlService.FOLLOWUP_VIEW;
import static com.compact.crm.security.AccessControlService.LEAD_MANAGE;
import static com.compact.crm.security.AccessControlService.LEAD_VIEW;
import static com.compact.crm.security.AccessControlService.OPPORTUNITY_MANAGE;
import static com.compact.crm.security.AccessControlService.OPPORTUNITY_VIEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The simplified Lead workflow (NEW / VALID / INVALID) on a real H2 database
 * with the real services: status and invalid-reason rules, legacy lead
 * handling, the display mapping and its filters, possible-duplicate
 * detection, contact deadlines and field-level history.
 */
@DataJpaTest
@Import({LeadService.class, OpportunityService.class, FollowUpService.class, CustomerService.class, SalesStageService.class,
        ActivityLogService.class, AccessControlService.class, CurrentUserService.class})
class LeadWorkflowIntegrationTest {

    @Autowired private LeadService leadService;
    @Autowired private OpportunityService opportunityService;
    @Autowired private SalesStageService salesStageService;

    @Autowired private LeadRepository leadRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private RolePermissionRepository rolePermissionRepository;
    @Autowired private IndustryRepository industryRepository;
    @Autowired private LeadSourceMasterRepository leadSourceMasterRepository;
    @Autowired private ActivityTypeRepository activityTypeRepository;
    @Autowired private FollowUpRepository followUpRepository;
    @Autowired private ActivityLogRepository activityLogRepository;

    private Employee admin;
    private Employee manager;
    private Employee employee;
    private Employee otherEmployee;
    private Industry industry;
    private LeadSourceMaster source;
    private ActivityType call;

    private final Map<String, Permission> permissions = new HashMap<>();

    @BeforeEach
    void setUp() {

        Role adminRole = roleRepository.save(Role.builder().name("ADMIN").rank(100).build());
        Role managerRole = roleRepository.save(Role.builder().name("MANAGER").rank(50).build());
        Role employeeRole = roleRepository.save(Role.builder().name("EMPLOYEE").rank(10).build());

        for (String code : List.of(LEAD_MANAGE, LEAD_VIEW, OPPORTUNITY_MANAGE, OPPORTUNITY_VIEW, FOLLOWUP_VIEW,
                com.compact.crm.security.AccessControlService.FOLLOWUP_MANAGE)) {
            grant(adminRole, code, Scope.ALL);
            grant(managerRole, code, Scope.TEAM);
            grant(employeeRole, code, Scope.OWN);
        }

        admin = employeeRepository.save(Employee.builder()
                .name("Admin").email("a@t.com").phone("9400000001").password("x").role(adminRole).build());
        manager = employeeRepository.save(Employee.builder()
                .name("Manager").email("m@t.com").phone("9400000002").password("x").role(managerRole).build());
        employee = employeeRepository.save(Employee.builder()
                .name("Employee").email("e@t.com").phone("9400000003").password("x").role(employeeRole)
                .manager(manager).build());
        otherEmployee = employeeRepository.save(Employee.builder()
                .name("Other").email("o@t.com").phone("9400000004").password("x").role(employeeRole).build());

        industry = industryRepository.save(Industry.builder().name("Manufacturing").build());
        source = leadSourceMasterRepository.save(LeadSourceMaster.builder().name("Referral").build());
        call = activityTypeRepository.save(ActivityType.builder().name("Call").build());

        salesStageService.ensureCoreStages();

        authenticateAs(admin);
    }

    private void grant(Role role, String code, Scope scope) {

        Permission p = permissions.computeIfAbsent(
                code, c -> permissionRepository.save(Permission.builder().code(c).build()));

        rolePermissionRepository.save(RolePermission.builder().role(role).permission(p).scope(scope).build());
    }

    private void authenticateAs(Employee actor) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor.getEmail(), null, List.of()));
    }

    private LeadRequest request(String company, LeadStatus status, Employee owner) {

        LeadRequest r = new LeadRequest();
        r.setCompanyName(company);
        r.setContactPerson("Contact");
        r.setDesignation("Manager");
        r.setPhone("98765" + String.format("%05d", Math.abs(company.hashCode()) % 100000));
        r.setCity("Pune");
        r.setState("MH");
        r.setIndustryId(industry.getId());
        r.setLeadSourceId(source.getId());
        r.setLeadStatus(status);
        r.setAssignedEmployeeId(owner.getId());
        return r;
    }

    // A lead as the old code stored it - any status, written directly.
    private Lead stored(String company, LeadStatus status, Employee owner) {

        return leadRepository.save(Lead.builder()
                .companyName(company).contactPerson("C").phone("9" + String.format("%09d", Math.abs(company.hashCode()) % 1000000000))
                .email(company.toLowerCase().replace(" ", "") + "@x.test")
                .city("Pune").state("MH").industry(industry).leadSource(source)
                .leadStatus(status).leadValidity(LeadValidity.VALID).assignedEmployee(owner).build());
    }

    private List<ActivityLog> leadHistory(Long id) {

        return activityLogRepository.findAll().stream()
                .filter(a -> a.getModule() == ActivityModule.LEAD && id.equals(a.getEntityId()))
                .toList();
    }

    private OpportunityRequest oppRequest() {

        OpportunityRequest r = new OpportunityRequest();
        r.setTitle("Deal");
        r.setExpectedClosingDate(BusinessClock.today().plusDays(30));
        r.setSalesStage("IN PROGRESS");
        r.setLeadValidity(LeadValidity.VALID);
        // an active opportunity needs a next action - supplied with the request
        r.setNextActionDescription("Call to discuss the proposal");
        r.setNextFollowUpDate(LocalDateTime.now().plusDays(7));
        r.setNextActionActivityTypeId(call.getId());
        return r;
    }

    // ===================================================== statuses and the invalid reason

    @Test
    void leadsCanBeCreatedAsNewValidOrInvalid_andValidityFollowsTheStatus() {

        Lead n = leadService.createLead(request("NewCo", LeadStatus.NEW, admin));
        Lead v = leadService.createLead(request("ValidCo", LeadStatus.VALID, admin));

        LeadRequest invalid = request("InvalidCo", LeadStatus.INVALID, admin);
        invalid.setInvalidReason("  Wrong number - not a customer  ");
        Lead i = leadService.createLead(invalid);

        assertThat(n.getLeadStatus()).isEqualTo(LeadStatus.NEW);
        assertThat(n.getLeadValidity()).isEqualTo(LeadValidity.VALID);   // "not invalid"
        assertThat(v.getLeadStatus()).isEqualTo(LeadStatus.VALID);
        assertThat(v.getLeadValidity()).isEqualTo(LeadValidity.VALID);
        assertThat(i.getLeadStatus()).isEqualTo(LeadStatus.INVALID);
        assertThat(i.getLeadValidity()).isEqualTo(LeadValidity.INVALID);
        assertThat(i.getInvalidReason()).isEqualTo("Wrong number - not a customer");
        assertThat(n.getInvalidReason()).isNull();
        assertThat(v.getInvalidReason()).isNull();
    }

    @Test
    void anOlderLegacyStatus_cannotBeChosenForANewLead() {

        for (LeadStatus legacy : List.of(LeadStatus.CONTACTED, LeadStatus.QUOTATION_SENT, LeadStatus.NEGOTIATION,
                LeadStatus.WON, LeadStatus.LOST, LeadStatus.DROPPED, LeadStatus.UNRESPONSIVE, LeadStatus.INACTIVE)) {

            assertThatThrownBy(() -> leadService.createLead(request("Legacy" + legacy, legacy, admin)))
                    .as(legacy.name())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("New, Valid or Invalid");
        }

        assertThat(leadRepository.count()).isZero();
    }

    @Test
    void invalidLead_requiresAReason_blankAndWhitespaceRejected() {

        for (String blank : new String[]{null, "", "   ", "\n\t"}) {

            LeadRequest r = request("NoReason", LeadStatus.INVALID, admin);
            r.setInvalidReason(blank);

            assertThatThrownBy(() -> leadService.createLead(r))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("reason this lead is invalid");
        }

        assertThat(leadRepository.count()).isZero();
    }

    @Test
    void anUnansweredCall_neverMakesALeadInvalid_itStaysNewWithItsFollowUpDate() {

        Lead lead = leadService.createLead(request("NoAnswer", LeadStatus.NEW, admin));

        // The contact attempt is a follow-up / note, not a status change.
        followUpRepository.save(FollowUp.builder().lead(lead).employee(admin).activityType(call)
                .status(FollowUpStatus.MISSED).scheduledDate(LocalDateTime.now().minusHours(2))
                .remarks("No answer, try again tomorrow").build());

        followUpRepository.save(FollowUp.builder().lead(lead).employee(admin).activityType(call)
                .status(FollowUpStatus.PENDING).scheduledDate(LocalDateTime.now().plusDays(1)).build());

        Lead reloaded = leadService.getLeadById(lead.getId());

        assertThat(reloaded.getLeadStatus()).isEqualTo(LeadStatus.NEW);
        assertThat(reloaded.getEffectiveLeadStatus()).isEqualTo(LeadStatus.NEW);
        assertThat(reloaded.getInvalidReason()).isNull();
        assertThat(followUpRepository.findByLeadId(lead.getId())).hasSize(2);
    }

    @Test
    void movingToInvalidNeedsAReason_andARejectedUpdateLeavesTheLeadUntouchedWithNoHistory() {

        Lead lead = leadService.createLead(request("ToInvalid", LeadStatus.NEW, admin));
        long historyBefore = leadHistory(lead.getId()).size();

        LeadRequest bad = request("ToInvalid", LeadStatus.INVALID, admin);
        bad.setPhone(lead.getPhone());

        assertThatThrownBy(() -> leadService.updateLead(lead.getId(), bad))
                .isInstanceOf(IllegalArgumentException.class);

        Lead reloaded = leadRepository.findById(lead.getId()).orElseThrow();
        assertThat(reloaded.getLeadStatus()).isEqualTo(LeadStatus.NEW);
        assertThat(reloaded.getLeadValidity()).isEqualTo(LeadValidity.VALID);
        assertThat(leadHistory(lead.getId())).hasSize((int) historyBefore);

        LeadRequest ok = request("ToInvalid", LeadStatus.INVALID, admin);
        ok.setPhone(lead.getPhone());
        ok.setInvalidReason("Not relevant to our products");

        Lead updated = leadService.updateLead(lead.getId(), ok);

        assertThat(updated.getLeadStatus()).isEqualTo(LeadStatus.INVALID);
        assertThat(updated.getLeadValidity()).isEqualTo(LeadValidity.INVALID);
        assertThat(updated.getInvalidReason()).isEqualTo("Not relevant to our products");
    }

    @Test
    void reopeningAnInvalidLead_clearsTheReason_butTheHistoryKeepsIt() {

        LeadRequest r = request("Reopen", LeadStatus.INVALID, admin);
        r.setInvalidReason("Looked like spam");
        Lead lead = leadService.createLead(r);

        LeadRequest back = request("Reopen", LeadStatus.NEW, admin);
        back.setPhone(lead.getPhone());
        Lead reopened = leadService.updateLead(lead.getId(), back);

        assertThat(reopened.getLeadStatus()).isEqualTo(LeadStatus.NEW);
        assertThat(reopened.getLeadValidity()).isEqualTo(LeadValidity.VALID);
        assertThat(reopened.getInvalidReason()).isNull();

        assertThat(leadHistory(lead.getId())).anyMatch(a -> "invalidReason".equals(a.getFieldName())
                && "Looked like spam".equals(a.getOldValue()) && a.getNewValue() == null);
        assertThat(leadHistory(lead.getId())).anyMatch(a -> "leadStatus".equals(a.getFieldName())
                && "INVALID".equals(a.getOldValue()) && "NEW".equals(a.getNewValue()));
    }

    // ===================================================== legacy leads

    @Test
    void legacyLead_staysEditableWithItsOriginalStatus_butCannotBeMovedToAnotherLegacyOne() {

        Lead old = stored("OldQuote", LeadStatus.QUOTATION_SENT, admin);

        // An unrelated edit that keeps the status as it is must work.
        LeadRequest same = request("OldQuote", LeadStatus.QUOTATION_SENT, admin);
        same.setPhone(old.getPhone());
        same.setCity("Mumbai");

        Lead saved = leadService.updateLead(old.getId(), same);

        assertThat(saved.getLeadStatus()).isEqualTo(LeadStatus.QUOTATION_SENT);   // never rewritten
        assertThat(saved.getCity()).isEqualTo("Mumbai");
        assertThat(saved.getLegacyStatus()).isEqualTo(LeadStatus.QUOTATION_SENT);

        // ...but nobody can newly pick a different legacy status
        LeadRequest other = request("OldQuote", LeadStatus.WON, admin);
        other.setPhone(old.getPhone());

        assertThatThrownBy(() -> leadService.updateLead(old.getId(), other))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("New, Valid or Invalid");

        assertThat(leadRepository.findById(old.getId()).orElseThrow().getLeadStatus())
                .isEqualTo(LeadStatus.QUOTATION_SENT);

        // ...while choosing VALID is the way forward, and then it can convert
        LeadRequest valid = request("OldQuote", LeadStatus.VALID, admin);
        valid.setPhone(old.getPhone());
        leadService.updateLead(old.getId(), valid);

        assertThat(opportunityService.convertLeadToOpportunity(old.getId(), oppRequest()).getId()).isNotNull();
    }

    @Test
    void historicalInvalidLeadWithoutAReason_staysEditable_withoutInventingOne() {

        Lead old = stored("OldInvalid", LeadStatus.INVALID, admin);
        old.setLeadValidity(LeadValidity.INVALID);
        leadRepository.save(old);

        LeadRequest edit = request("OldInvalid", LeadStatus.INVALID, admin);
        edit.setPhone(old.getPhone());
        edit.setCity("Delhi");

        Lead saved = leadService.updateLead(old.getId(), edit);

        assertThat(saved.getCity()).isEqualTo("Delhi");
        assertThat(saved.getInvalidReason()).isNull();
    }

    @Test
    void aLegacyLeadWithAnOpportunity_isShownAsValid_otherLegacyLeadsAsNew_andNothingIsRewritten() {

        Lead contacted = stored("LegacyContacted", LeadStatus.CONTACTED, admin);
        Lead dropped = stored("LegacyDropped", LeadStatus.DROPPED, admin);
        Lead inactive = stored("LegacyInactive", LeadStatus.INACTIVE, admin);
        Lead wonWithOpp = stored("LegacyWon", LeadStatus.WON, admin);

        wonWithOpp.setLeadValidity(LeadValidity.VALID);
        leadRepository.save(wonWithOpp);
        attachOpportunity(wonWithOpp);

        Page<Lead> page = leadService.searchLeads(new LeadSearchCriteria(), 0, 50, null, null);

        Map<String, Lead> byName = new HashMap<>();
        page.getContent().forEach(l -> byName.put(l.getCompanyName(), l));

        assertThat(byName.get("LegacyContacted").getEffectiveLeadStatus()).isEqualTo(LeadStatus.NEW);
        assertThat(byName.get("LegacyDropped").getEffectiveLeadStatus()).isEqualTo(LeadStatus.NEW);
        assertThat(byName.get("LegacyInactive").getEffectiveLeadStatus()).isEqualTo(LeadStatus.NEW);
        assertThat(byName.get("LegacyWon").getEffectiveLeadStatus()).isEqualTo(LeadStatus.VALID);

        // the original value stays visible and untouched
        assertThat(byName.get("LegacyDropped").getLegacyStatus()).isEqualTo(LeadStatus.DROPPED);
        assertThat(leadRepository.findById(dropped.getId()).orElseThrow().getLeadStatus()).isEqualTo(LeadStatus.DROPPED);
        assertThat(leadRepository.findById(contacted.getId()).orElseThrow().getLeadStatus()).isEqualTo(LeadStatus.CONTACTED);
        assertThat(leadRepository.findById(inactive.getId()).orElseThrow().getLeadStatus()).isEqualTo(LeadStatus.INACTIVE);
    }

    private void attachOpportunity(Lead lead) {

        com.compact.crm.entity.SalesStage stage = salesStageService.getByType(com.compact.crm.enums.SalesStageType.IN_PROGRESS);

        opportunityRepositoryForTest.save(com.compact.crm.entity.Opportunity.builder()
                .title("Legacy deal").productValue(1.0).expectedClosingDate(BusinessClock.today().plusDays(5))
                .salesStage(stage).leadValidity(LeadValidity.VALID).lead(lead).build());
    }

    @Autowired private com.compact.crm.repository.OpportunityRepository opportunityRepositoryForTest;

    @Test
    void statusGroupFilter_findsLeadsUnderTheGroupTheyAreShownIn() {

        stored("A new", LeadStatus.NEW, admin);
        stored("A valid", LeadStatus.VALID, admin);
        Lead inv = stored("An invalid", LeadStatus.INVALID, admin);
        stored("Legacy contacted", LeadStatus.CONTACTED, admin);
        Lead legacyConverted = stored("Legacy converted", LeadStatus.NEGOTIATION, admin);
        attachOpportunity(legacyConverted);

        assertThat(names(LeadStatus.NEW)).containsExactlyInAnyOrder("A new", "Legacy contacted");
        assertThat(names(LeadStatus.VALID)).containsExactlyInAnyOrder("A valid", "Legacy converted");
        assertThat(names(LeadStatus.INVALID)).containsExactly("An invalid");

        // every lead appears in exactly one group
        assertThat(names(LeadStatus.NEW).size() + names(LeadStatus.VALID).size() + names(LeadStatus.INVALID).size())
                .isEqualTo((int) leadRepository.count());

        // the stored-status filter used by the legacy pages is unchanged
        LeadSearchCriteria stored = new LeadSearchCriteria();
        stored.setStatus(LeadStatus.CONTACTED);
        assertThat(leadService.searchLeads(stored, 0, 50, null, null).getContent())
                .extracting(Lead::getCompanyName).containsExactly("Legacy contacted");

        // pagination keeps working with the group filter
        LeadSearchCriteria c = new LeadSearchCriteria();
        c.setStatusGroup(LeadStatus.NEW);
        Page<Lead> p0 = leadService.searchLeads(c, 0, 1, null, null);
        assertThat(p0.getTotalElements()).isEqualTo(2);
        assertThat(p0.getTotalPages()).isEqualTo(2);
        assertThat(inv).isNotNull();
    }

    private List<String> names(LeadStatus group) {

        LeadSearchCriteria c = new LeadSearchCriteria();
        c.setStatusGroup(group);

        return leadService.searchLeads(c, 0, 100, null, null).getContent().stream()
                .map(Lead::getCompanyName).toList();
    }

    // ===================================================== conversion eligibility + converted flag

    @Test
    void conversionBlockedReason_explainsWhyForEachCase() {

        Lead n = leadService.createLead(request("NewOne", LeadStatus.NEW, admin));
        LeadRequest inv = request("InvOne", LeadStatus.INVALID, admin);
        inv.setInvalidReason("Spam");
        Lead i = leadService.createLead(inv);
        Lead legacy = stored("LegacyOne", LeadStatus.CONTACTED, admin);
        Lead v = leadService.createLead(request("ValidOne", LeadStatus.VALID, admin));

        assertThat(leadService.getLeadById(n.getId()).getConversionBlockedReason()).contains("Mark the lead VALID first");
        assertThat(leadService.getLeadById(i.getId()).getConversionBlockedReason()).contains("marked INVALID");
        assertThat(leadService.getLeadById(legacy.getId()).getConversionBlockedReason()).contains("older status CONTACTED");
        assertThat(leadService.getLeadById(v.getId()).getConversionBlockedReason()).isNull();

        opportunityService.convertLeadToOpportunity(v.getId(), oppRequest());

        Lead converted = leadService.getLeadById(v.getId());
        assertThat(converted.getConverted()).isTrue();
        assertThat(converted.getConversionBlockedReason()).contains("already been converted");
        assertThat(converted.getEffectiveLeadStatus()).isEqualTo(LeadStatus.VALID);

        // the backend still rejects a repeat cleanly
        assertThatThrownBy(() -> opportunityService.convertLeadToOpportunity(v.getId(), oppRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already been converted");
    }

    @Test
    void onlyAValidLeadConverts_newAndInvalidAreRejected() {

        Lead n = leadService.createLead(request("NewOnly", LeadStatus.NEW, admin));

        assertThatThrownBy(() -> opportunityService.convertLeadToOpportunity(n.getId(), oppRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only VALID leads can be converted")
                .hasMessageContaining("Mark the lead VALID first");

        LeadRequest r = request("NewOnly", LeadStatus.VALID, admin);
        r.setPhone(n.getPhone());
        leadService.updateLead(n.getId(), r);

        assertThat(opportunityService.convertLeadToOpportunity(n.getId(), oppRequest()).getId()).isNotNull();
    }

    // ===================================================== contact deadline

    @Test
    void newLeads_getADefaultContactDeadline_theUserMayOverride_andHistoricalLeadsHaveNone() {

        Lead defaulted = leadService.createLead(request("Defaulted", LeadStatus.NEW, admin));

        assertThat(defaulted.getContactDeadline())
                .isEqualTo(BusinessClock.today().plusDays(LeadWorkflow.DEFAULT_CONTACT_DEADLINE_DAYS));

        LeadRequest explicit = request("Explicit", LeadStatus.NEW, admin);
        explicit.setContactDeadline(BusinessClock.today().plusDays(9));

        assertThat(leadService.createLead(explicit).getContactDeadline()).isEqualTo(BusinessClock.today().plusDays(9));

        assertThat(stored("Historical", LeadStatus.NEW, admin).getContactDeadline()).isNull();

        // an update that does not send the field never wipes it
        LeadRequest edit = request("Defaulted", LeadStatus.NEW, admin);
        edit.setPhone(defaulted.getPhone());
        assertThat(leadService.updateLead(defaulted.getId(), edit).getContactDeadline())
                .isEqualTo(BusinessClock.today().plusDays(LeadWorkflow.DEFAULT_CONTACT_DEADLINE_DAYS));
    }

    @Test
    void contactOverdue_onlyForNewLeadsPastTheirDeadlineWithNoCompletedFollowUp() {

        Lead overdue = stored("Overdue", LeadStatus.NEW, admin);
        overdue.setContactDeadline(BusinessClock.today().minusDays(1));
        leadRepository.save(overdue);

        Lead contacted = stored("Contacted", LeadStatus.NEW, admin);
        contacted.setContactDeadline(BusinessClock.today().minusDays(1));
        leadRepository.save(contacted);
        followUpRepository.save(FollowUp.builder().lead(contacted).employee(admin).activityType(call)
                .status(FollowUpStatus.COMPLETED).scheduledDate(LocalDateTime.now().minusDays(1)).build());

        Lead future = stored("FutureDeadline", LeadStatus.NEW, admin);
        future.setContactDeadline(BusinessClock.today().plusDays(1));
        leadRepository.save(future);

        Lead valid = stored("QualifiedAlready", LeadStatus.VALID, admin);
        valid.setContactDeadline(BusinessClock.today().minusDays(5));
        leadRepository.save(valid);

        Lead noDeadline = stored("NoDeadline", LeadStatus.NEW, admin);

        Map<String, Lead> byName = new HashMap<>();
        leadService.searchLeads(new LeadSearchCriteria(), 0, 50, null, null).getContent()
                .forEach(l -> byName.put(l.getCompanyName(), l));

        assertThat(byName.get("Overdue").getContactOverdue()).isTrue();
        assertThat(byName.get("Contacted").getContactOverdue()).isFalse();
        assertThat(byName.get("FutureDeadline").getContactOverdue()).isFalse();
        assertThat(byName.get("QualifiedAlready").getContactOverdue()).isFalse();
        assertThat(byName.get("NoDeadline").getContactOverdue()).isFalse();
        assertThat(noDeadline).isNotNull();
    }

    // ===================================================== history

    @Test
    void history_recordsStatusReasonOwnerAndDeadlineChanges_withActorAndValues() {

        Lead lead = leadService.createLead(request("History", LeadStatus.NEW, employee));

        authenticateAs(manager);

        LeadRequest edit = request("History", LeadStatus.INVALID, otherEmployee);
        edit.setPhone(lead.getPhone());
        edit.setInvalidReason("Duplicate of an existing customer");
        edit.setContactDeadline(BusinessClock.today().plusDays(7));

        // the manager may not reassign outside the team: ownership stays
        leadService.updateLead(lead.getId(), edit);

        List<ActivityLog> history = leadHistory(lead.getId());

        ActivityLog status = history.stream().filter(a -> "leadStatus".equals(a.getFieldName())).findFirst().orElseThrow();
        assertThat(status.getOldValue()).isEqualTo("NEW");
        assertThat(status.getNewValue()).isEqualTo("INVALID");
        assertThat(status.getEmployeeName()).isEqualTo("Manager");
        assertThat(status.getCreatedAt()).isNotNull();

        assertThat(history).anyMatch(a -> "invalidReason".equals(a.getFieldName())
                && a.getOldValue() == null && "Duplicate of an existing customer".equals(a.getNewValue()));
        assertThat(history).anyMatch(a -> "contactDeadline".equals(a.getFieldName())
                && BusinessClock.today().plusDays(7).toString().equals(a.getNewValue()));
    }

    @Test
    void history_recordsReassignment_soOwnershipHistorySurvives() {

        Lead lead = leadService.createLead(request("Reassign", LeadStatus.NEW, employee));

        LeadRequest edit = request("Reassign", LeadStatus.NEW, otherEmployee);
        edit.setPhone(lead.getPhone());
        leadService.updateLead(lead.getId(), edit);

        assertThat(leadHistory(lead.getId())).anyMatch(a -> "assignedEmployee".equals(a.getFieldName())
                && "Employee".equals(a.getOldValue()) && "Other".equals(a.getNewValue())
                && "Admin".equals(a.getEmployeeName()));
    }

    @Test
    void history_aSaveThatChangesNoTrackedField_keepsTheGenericEntry() {

        Lead lead = leadService.createLead(request("Generic", LeadStatus.NEW, admin));

        LeadRequest edit = request("Generic", LeadStatus.NEW, admin);
        edit.setPhone(lead.getPhone());
        edit.setCity("Nagpur");
        leadService.updateLead(lead.getId(), edit);

        assertThat(leadHistory(lead.getId())).anyMatch(a -> "Updated lead".equals(a.getDescription()));
    }

    // ===================================================== possible duplicates

    @Test
    void duplicates_matchOnPhoneEmailAndCompany_andReportWhatMatched() {

        Lead existing = leadService.createLead(buildFull("Acme Industries", "9876501234", "sales@acme.test"));

        DuplicateLeadsResponse byPhone = leadService.findPossibleDuplicates("98765 01234", null, null, null);
        assertThat(byPhone.matches()).extracting(DuplicateLeadsResponse.Match::id).containsExactly(existing.getId());
        assertThat(byPhone.matches().get(0).matchedOn()).containsExactly("phone");

        DuplicateLeadsResponse byEmail = leadService.findPossibleDuplicates(null, "  SALES@acme.TEST ", null, null);
        assertThat(byEmail.matches()).hasSize(1);
        assertThat(byEmail.matches().get(0).matchedOn()).containsExactly("email");

        DuplicateLeadsResponse byCompany = leadService.findPossibleDuplicates(null, null, " acme industries ", null);
        assertThat(byCompany.matches()).hasSize(1);
        assertThat(byCompany.matches().get(0).matchedOn()).containsExactly("company");

        DuplicateLeadsResponse all = leadService.findPossibleDuplicates("9876501234", "sales@acme.test", "Acme Industries", null);
        assertThat(all.matches().get(0).matchedOn()).containsExactlyInAnyOrder("phone", "email", "company");

        // editing the lead itself does not flag itself
        assertThat(leadService.findPossibleDuplicates("9876501234", null, null, existing.getId()).matches()).isEmpty();

        // nothing in common
        assertThat(leadService.findPossibleDuplicates("9000000000", "other@else.test", "Totally Different Ltd", null)
                .matches()).isEmpty();
    }

    @Test
    void duplicates_blankMissingOrTooShortInputsMatchNothing_andNeverFail() {

        leadService.createLead(buildFull("Acme Industries", "9876501234", "sales@acme.test"));

        assertThat(leadService.findPossibleDuplicates(null, null, null, null).matches()).isEmpty();
        assertThat(leadService.findPossibleDuplicates("", "", "", null).matches()).isEmpty();
        assertThat(leadService.findPossibleDuplicates("123", "not-an-email", "ab", null).matches()).isEmpty();
        assertThat(leadService.findPossibleDuplicates("   ", "   ", "   ", null).hiddenCount()).isZero();
    }

    @Test
    void duplicates_onlyDescribeLeadsTheCallerMayView_othersAreJustCounted() {

        authenticateAs(otherEmployee);
        leadService.createLead(buildFullFor("Shared Name Ltd", "9111100001", "shared1@x.test", otherEmployee));

        authenticateAs(admin);
        leadService.createLead(buildFullFor("Shared Name Ltd", "9111100002", "shared2@x.test", admin));

        // the employee only sees their own lead described; the admin's lead is only counted
        authenticateAs(otherEmployee);
        DuplicateLeadsResponse seen = leadService.findPossibleDuplicates(null, null, "Shared Name Ltd", null);

        assertThat(seen.matches()).hasSize(1);
        assertThat(seen.matches().get(0).ownerName()).isEqualTo("Other");
        assertThat(seen.hiddenCount()).isEqualTo(1);

        // the admin sees both and nothing hidden
        authenticateAs(admin);
        DuplicateLeadsResponse all = leadService.findPossibleDuplicates(null, null, "Shared Name Ltd", null);

        assertThat(all.matches()).hasSize(2);
        assertThat(all.hiddenCount()).isZero();
    }

    private LeadRequest buildFull(String company, String phone, String email) {
        return buildFullFor(company, phone, email, admin);
    }

    private LeadRequest buildFullFor(String company, String phone, String email, Employee owner) {

        LeadRequest r = request(company, LeadStatus.NEW, owner);
        r.setPhone(phone);
        r.setEmail(email);
        return r;
    }

    @Test
    void duplicates_showAConvertedLegacyLeadAsValid_andAnUnconvertedOneAsNew() {

        Lead converted = stored("Same Name Traders", LeadStatus.CONTACTED, admin);
        attachOpportunity(converted);

        // a different lead with the same company name that was never converted
        Lead plain = stored("Same Name Traders", LeadStatus.DROPPED, admin);

        DuplicateLeadsResponse r = leadService.findPossibleDuplicates(null, null, "same name traders", null);

        Map<Long, String> statusById = new HashMap<>();
        r.matches().forEach(m -> statusById.put(m.id(), m.status()));

        assertThat(statusById.get(converted.getId())).isEqualTo("VALID");   // has an opportunity
        assertThat(statusById.get(plain.getId())).isEqualTo("NEW");         // legacy DROPPED, never converted
    }

    // ===================================================== stale leads are read-only (no scheduler)

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    // Backdates updatedAt with a bulk update (a normal save() would re-stamp it).
    private void backdate(Lead lead, int months) {

        entityManager.createQuery("UPDATE Lead l SET l.updatedAt = :dt WHERE l.id = :id")
                .setParameter("dt", LocalDateTime.now().minusMonths(months))
                .setParameter("id", lead.getId())
                .executeUpdate();

        entityManager.clear();
    }

    private List<Lead> inactivePage() {

        LeadSearchCriteria c = new LeadSearchCriteria();
        c.setInactiveView(true);

        return leadService.searchLeads(c, 0, 100, null, null).getContent();
    }

    @Test
    void theStaleLeadSchedulerNoLongerExists() {

        assertThatThrownBy(() -> Class.forName("com.compact.crm.scheduler.StaleLeadScheduler"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void staleLead_isListedOnTheInactivePage_flagged_withoutChangingItsStatus() {

        Lead stale = stored("Stale NEW Co", LeadStatus.NEW, admin);
        backdate(stale, 8);
        stored("Fresh Co", LeadStatus.NEW, admin);

        List<Lead> page = inactivePage();

        assertThat(page).extracting(Lead::getCompanyName).containsExactly("Stale NEW Co");
        assertThat(page.get(0).getStale()).isTrue();

        Lead reloaded = leadRepository.findById(stale.getId()).orElseThrow();
        assertThat(reloaded.getLeadStatus()).isEqualTo(LeadStatus.NEW);
        assertThat(reloaded.getLeadStatus()).isNotEqualTo(LeadStatus.INVALID);
        assertThat(leadHistory(stale.getId())).isEmpty();
    }

    @Test
    void storedInactiveLead_stillAppears_andIsNotMarkedStale_andNotDuplicated() {

        Lead legacy = stored("Legacy Inactive Co", LeadStatus.INACTIVE, admin);
        backdate(legacy, 12);   // old as well, matches both conditions
        Lead stale = stored("Stale Only Co", LeadStatus.NEW, admin);
        backdate(stale, 9);

        List<Lead> page = inactivePage();

        assertThat(page).extracting(Lead::getCompanyName)
                .containsExactlyInAnyOrder("Legacy Inactive Co", "Stale Only Co");

        Lead legacyRow = page.stream().filter(l -> l.getId().equals(legacy.getId())).findFirst().orElseThrow();
        assertThat(legacyRow.getLeadStatus()).isEqualTo(LeadStatus.INACTIVE);
        assertThat(legacyRow.getStale()).isFalse();
    }

    @Test
    void recentFollowUp_orAnOpportunity_keepsALeadOffTheStaleList() {

        Lead followed = stored("Followed Co", LeadStatus.NEW, admin);
        backdate(followed, 8);
        followUpRepository.save(FollowUp.builder().lead(followed).employee(admin).activityType(call)
                .status(FollowUpStatus.COMPLETED).scheduledDate(LocalDateTime.now().minusDays(1)).build());

        Lead converted = stored("Converted Co", LeadStatus.VALID, admin);
        opportunityService.convertLeadToOpportunity(converted.getId(), oppRequest());
        backdate(converted, 8);

        assertThat(inactivePage()).isEmpty();
    }

    @Test
    void staleValidAndInvalidAndResolvedLeads_areNotChangedOrMisclassified() {

        Lead valid = stored("Old Valid Co", LeadStatus.VALID, admin);
        backdate(valid, 10);
        Lead invalid = stored("Old Invalid Co", LeadStatus.INVALID, admin);
        backdate(invalid, 10);

        // VALID is not an "open" legacy status, so only the legacy open
        // statuses (NEW/CONTACTED/QUOTATION_SENT/NEGOTIATION) go stale.
        assertThat(inactivePage()).isEmpty();
        assertThat(leadRepository.findById(valid.getId()).orElseThrow().getLeadStatus()).isEqualTo(LeadStatus.VALID);
        assertThat(leadRepository.findById(invalid.getId()).orElseThrow().getLeadStatus()).isEqualTo(LeadStatus.INVALID);
    }

    @Test
    void staleDoesNotChangeConversion_onlyValidConverts() {

        Lead staleNew = stored("Stale New Convert", LeadStatus.NEW, admin);
        backdate(staleNew, 9);

        assertThatThrownBy(() -> opportunityService.convertLeadToOpportunity(staleNew.getId(), oppRequest()))
                .isInstanceOf(IllegalArgumentException.class);

        // once validated it converts, however old it is
        Lead reloaded = leadRepository.findById(staleNew.getId()).orElseThrow();
        reloaded.setLeadStatus(LeadStatus.VALID);
        leadRepository.save(reloaded);
        backdate(reloaded, 9);

        assertThat(opportunityService.convertLeadToOpportunity(staleNew.getId(), oppRequest()).getId()).isNotNull();
    }

    @Test
    void inactivePage_isRoleScoped_searchable_andPaginated() {

        Lead mine = stored("Employee Stale Co", LeadStatus.NEW, employee);
        backdate(mine, 8);
        Lead others = stored("Other Stale Co", LeadStatus.NEW, otherEmployee);
        backdate(others, 8);
        Lead third = stored("Another Employee Stale", LeadStatus.NEW, employee);
        backdate(third, 8);

        authenticateAs(employee);
        assertThat(inactivePage()).extracting(Lead::getCompanyName)
                .containsExactlyInAnyOrder("Employee Stale Co", "Another Employee Stale");

        authenticateAs(manager);
        assertThat(inactivePage()).extracting(Lead::getCompanyName)
                .containsExactlyInAnyOrder("Employee Stale Co", "Another Employee Stale");

        authenticateAs(admin);
        assertThat(inactivePage()).hasSize(3);

        LeadSearchCriteria c = new LeadSearchCriteria();
        c.setInactiveView(true);
        assertThat(leadService.searchLeads(c, 0, 2, null, null).getTotalElements()).isEqualTo(3);
        assertThat(leadService.searchLeads(c, 0, 2, null, null).getContent()).hasSize(2);
    }

}
