package com.compact.crm.service;

import com.compact.crm.dto.request.FollowUpRequest;
import com.compact.crm.dto.request.LeadBatteryRequest;
import com.compact.crm.dto.request.LeadProductRequest;
import com.compact.crm.dto.request.OpportunityRequest;
import com.compact.crm.dto.request.ReturnToInProgressRequest;
import com.compact.crm.dto.response.BulkOperationResult;
import com.compact.crm.dto.response.DeleteImpactResponse;
import com.compact.crm.dto.response.WorkflowAlertsResponse;
import com.compact.crm.dto.search.OpportunitySearchCriteria;
import com.compact.crm.entity.Battery;
import com.compact.crm.entity.Customer;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.FollowUp;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.LeadBattery;
import com.compact.crm.entity.LeadProduct;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.entity.OpportunityBattery;
import com.compact.crm.entity.OpportunityProduct;
import com.compact.crm.entity.Product;
import com.compact.crm.entity.SalesStage;
import com.compact.crm.enums.ActivityAction;
import com.compact.crm.enums.ActivityModule;
import com.compact.crm.enums.FollowUpStatus;
import com.compact.crm.enums.LeadValidity;
import com.compact.crm.enums.SalesStageType;
import com.compact.crm.exception.ResourceNotFoundException;
import com.compact.crm.repository.BatteryRepository;
import com.compact.crm.repository.CustomerRepository;
import com.compact.crm.repository.FollowUpRepository;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.OpportunityRepository;
import com.compact.crm.repository.ProductRepository;
import com.compact.crm.security.AccessControlService;
import com.compact.crm.security.CurrentUserService;
import com.compact.crm.specification.OpportunitySpecifications;
import com.compact.crm.specification.SpecificationUtil;
import com.compact.crm.util.BusinessClock;
import com.compact.crm.util.SortWhitelist;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.compact.crm.security.AccessControlService.LEAD_MANAGE;
import static com.compact.crm.security.AccessControlService.OPPORTUNITY_DELETE;
import static com.compact.crm.security.AccessControlService.OPPORTUNITY_EXPORT;
import static com.compact.crm.security.AccessControlService.OPPORTUNITY_MANAGE;
import static com.compact.crm.security.AccessControlService.OPPORTUNITY_VIEW;

@Service
@RequiredArgsConstructor
public class OpportunityService {

    private final OpportunityRepository opportunityRepository;
    private final LeadRepository leadRepository;
    private final CustomerRepository customerRepository;
    private final FollowUpRepository followUpRepository;
    private final CurrentUserService currentUserService;
    private final AccessControlService accessControlService;
    private final SalesStageService salesStageService;
    private final ActivityLogService activityLogService;
    private final FollowUpService followUpService;
    private final ProductRepository productRepository;
    private final BatteryRepository batteryRepository;

    // Still enforced on UPDATE (see updateOpportunity) - editing a WON
    // opportunity's stage away from WON while a Customer conversion already
    // exists would silently orphan that Customer's link. DELETE no longer
    // uses this: deleting a WON+converted Opportunity is now an explicit,
    // previewed cascade (see cascadeDeleteOpportunity/getDeleteImpact)
    // rather than being blocked outright.
    private static final String WON_LOCK_MESSAGE =
            "This opportunity has already been converted to a customer and cannot be moved out of Won.";


    private static final Map<String, String> SORT_FIELDS = Map.of(
            "title", "title",
            "value", "productValue",
            "closingDate", "expectedClosingDate",
            "createdDate", "createdAt",
            "updatedDate", "updatedAt",
            "stage", "salesStage.name",
            "assignedEmployee", "lead.assignedEmployee.name",
            "company", "lead.companyName",
            "contactPerson", "contactPerson"
    );

    private static final int EXPORT_MAX_ROWS = 20_000;

    // How many example opportunities each Dashboard reminder lists.
    private static final int ALERT_SAMPLE_SIZE = 5;

    /**
     * Convert a Lead into an Opportunity. Transactional: the opportunity and
     * the next-action follow-up supplied with it are saved together or not at
     * all.
     */
    @Transactional
    public Opportunity convertLeadToOpportunity(Long leadId, OpportunityRequest request) {

        Lead lead = leadRepository.findById(leadId)
                .orElseThrow(() -> new ResourceNotFoundException("Lead not found"));

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        accessControlService.assertCanAccess(
                currentEmployee,
                LEAD_MANAGE,
                lead.getAssignedEmployee().getId()
        );

        // Only a lead whose status is VALID (qualified, a genuine prospect) can
        // become an Opportunity, and only once. Checked on the stored lead -
        // not just the form - so a direct API call cannot bypass it. The
        // lead's legacy status values are untouched; see
        // LeadWorkflow.conversionBlockedReason for the wording shown to users.
        String blocked = LeadWorkflow.conversionBlockedReason(lead, opportunityRepository.existsByLeadId(leadId));

        if (blocked != null) {
            throw new IllegalArgumentException(blocked);
        }

        // The conversion form used to let the user flip the lead's validity
        // while converting; an invalid validity can never be the result.
        if (request.getLeadValidity() != LeadValidity.VALID) {
            throw new IllegalArgumentException(
                    "Only VALID leads can be converted into an opportunity.");
        }

        SalesStage salesStage = resolveStage(request.getSalesStage());

        Opportunity opportunity = Opportunity.builder()
                .title(request.getTitle())
                .productValue(request.getProductValue())
                .leadValidity(request.getLeadValidity())
                .lead(lead)
                .build();

        copyDetailsFromLead(opportunity, lead);

        applyWorkflowRules(opportunity, request, salesStage, true);

        // An active opportunity needs a qualifying pending follow-up: one the
        // lead already has, or the next action supplied with this request.
        NextActionInput nextAction = requireNextAction(null, lead, salesStage, nextActionOf(request));

        Opportunity saved;

        try {

            saved = opportunityRepository.save(opportunity);

        } catch (DataIntegrityViolationException e) {

            // Two conversions of the same lead raced past the check above and
            // the database's one-opportunity-per-lead index rejected the second.
            throw new IllegalArgumentException("This lead has already been converted into an Opportunity.");
        }

        createNextActionFollowUp(saved, nextAction);

        activityLogService.log(
                currentEmployee,
                ActivityModule.LEAD, ActivityAction.CONVERT,
                lead.getId(), lead.getCompanyName(),
                "Converted lead to opportunity: " + saved.getTitle()
        );

        // Start the Opportunity's own history (the entry above belongs to
        // the Lead's history).
        activityLogService.log(
                currentEmployee,
                ActivityModule.OPPORTUNITY, ActivityAction.CREATE,
                saved.getId(), saved.getTitle(),
                "Created opportunity from lead " + lead.getCompanyName()
                        + " (stage: " + salesStage.getName()
                        + ", expected closing date: " + saved.getExpectedClosingDate() + ")"
        );

        decorateNextAction(List.of(saved));

        return saved;
    }

    // The stage must already exist - only ADMIN creates stages (via
    // SalesStageController), so an Opportunity save never creates one.
    private SalesStage resolveStage(String name) {

        try {
            return salesStageService.getByName(name);
        } catch (ResourceNotFoundException e) {
            throw new IllegalArgumentException(
                    "Sales stage \"" + name + "\" does not exist. Ask an Admin to add it first.");
        }
    }

    private static String trimToNull(String value) {

        if (value == null) {
            return null;
        }

        String trimmed = value.trim();

        return trimmed.isEmpty() ? null : trimmed;
    }

    // ---- the Opportunity's own contact details and line items ----

    private static final java.util.regex.Pattern PHONE_PATTERN = java.util.regex.Pattern.compile("\\d{10}");

    private static final java.util.regex.Pattern EMAIL_PATTERN =
            java.util.regex.Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    // Conversion takes a copy of the Lead's contact details and product /
    // battery line items. From then on the Opportunity's copy is the one that
    // is edited and reported on; the Lead keeps its original values.
    // Line items missing a product/battery are skipped, never guessed.
    private static void copyDetailsFromLead(Opportunity opportunity, Lead lead) {

        opportunity.setContactPerson(lead.getContactPerson());
        opportunity.setContactPhone(lead.getPhone());
        opportunity.setContactEmail(lead.getEmail());

        for (LeadProduct lp : lead.getLeadProducts()) {

            if (lp.getProduct() != null && lp.getQuantity() != null) {
                opportunity.getOpportunityProducts().add(OpportunityProduct.builder()
                        .opportunity(opportunity).product(lp.getProduct()).quantity(lp.getQuantity()).build());
            }
        }

        for (LeadBattery lb : lead.getLeadBatteries()) {

            if (lb.getBattery() != null && lb.getQuantity() != null) {
                opportunity.getOpportunityBatteries().add(OpportunityBattery.builder()
                        .opportunity(opportunity).battery(lb.getBattery()).quantity(lb.getQuantity()).build());
            }
        }
    }

    // The contact / line-item part of an edit, validated and resolved before
    // anything on the entity is touched. A null field means "not sent - keep".
    private record DetailsEdit(
            String contactPerson, String contactPhone, String contactEmail,
            List<OpportunityProduct> products, List<OpportunityBattery> batteries) {
    }

    private DetailsEdit resolveDetailsEdit(Opportunity opportunity, OpportunityRequest request) {

        String person = editedText(request.getContactPerson(), opportunity.getContactPerson(),
                "Contact Person is required.", null, null);

        String phone = editedText(request.getContactPhone(), opportunity.getContactPhone(),
                "Contact Phone is required.", PHONE_PATTERN, "Contact Phone must contain exactly 10 digits.");

        String email = editedText(request.getContactEmail(), opportunity.getContactEmail(),
                null, EMAIL_PATTERN, "Please enter a valid Contact Email.");

        List<OpportunityProduct> products = null;

        if (request.getProducts() != null) {

            if (request.getProducts().isEmpty() && !opportunity.getOpportunityProducts().isEmpty()) {
                throw new IllegalArgumentException("Please keep at least one Product on the opportunity.");
            }

            products = new ArrayList<>();

            for (LeadProductRequest item : request.getProducts()) {

                if (item == null || item.getProductId() == null) {
                    throw new IllegalArgumentException("Please select a Product for every product row.");
                }

                Product product = productRepository.findById(item.getProductId())
                        .orElseThrow(() -> new ResourceNotFoundException("Product not found"));

                products.add(OpportunityProduct.builder()
                        .opportunity(opportunity).product(product).quantity(quantityOf(item.getQuantity())).build());
            }
        }

        List<OpportunityBattery> batteries = null;

        if (request.getBatteries() != null) {

            if (request.getBatteries().isEmpty() && !opportunity.getOpportunityBatteries().isEmpty()) {
                throw new IllegalArgumentException("Please keep at least one Battery on the opportunity.");
            }

            batteries = new ArrayList<>();

            for (LeadBatteryRequest item : request.getBatteries()) {

                if (item == null || item.getBatteryId() == null) {
                    throw new IllegalArgumentException("Please select a Battery for every battery row.");
                }

                Battery battery = batteryRepository.findById(item.getBatteryId())
                        .orElseThrow(() -> new ResourceNotFoundException("Battery not found"));

                batteries.add(OpportunityBattery.builder()
                        .opportunity(opportunity).battery(battery).quantity(quantityOf(item.getQuantity())).build());
            }
        }

        return new DetailsEdit(person, phone, email, products, batteries);
    }

    private static int quantityOf(Integer quantity) {

        if (quantity == null || quantity < 1) {
            throw new IllegalArgumentException("Quantity must be at least 1.");
        }

        return quantity;
    }

    // Resolves one edited text field. Returns the value to store, or the
    // current value when the field was not sent. A value that is cleared
    // while `requiredMessage` is set is rejected (an empty value that was
    // already empty stays allowed). The format is only checked for a value
    // that actually changes, so a historical value never blocks an edit.
    private static String editedText(String requested, String current, String requiredMessage,
                                     java.util.regex.Pattern format, String formatMessage) {

        if (requested == null) {
            return current;
        }

        String value = trimToNull(requested);

        if (value == null) {

            if (requiredMessage != null && trimToNull(current) != null) {
                throw new IllegalArgumentException(requiredMessage);
            }

            return null;
        }

        if (format != null && !value.equals(current) && !format.matcher(value).matches()) {
            throw new IllegalArgumentException(formatMessage);
        }

        return value;
    }

    private static void applyDetailsEdit(Opportunity opportunity, DetailsEdit edit) {

        opportunity.setContactPerson(edit.contactPerson());
        opportunity.setContactPhone(edit.contactPhone());
        opportunity.setContactEmail(edit.contactEmail());

        // Replaced in place (orphanRemoval deletes the old rows) so the
        // collection Hibernate tracks is never swapped out.
        if (edit.products() != null) {
            opportunity.getOpportunityProducts().clear();
            opportunity.getOpportunityProducts().addAll(edit.products());
        }

        if (edit.batteries() != null) {
            opportunity.getOpportunityBatteries().clear();
            opportunity.getOpportunityBatteries().addAll(edit.batteries());
        }
    }

    // "Name x qty; Name x qty" - how line items appear in the history and
    // the export.
    public static String describeProducts(Opportunity o) {

        return o.getOpportunityProducts().stream()
                .filter(op -> op.getProduct() != null)
                .map(op -> op.getProduct().getName() + " x" + op.getQuantity())
                .collect(java.util.stream.Collectors.joining("; "));
    }

    public static String describeBatteries(Opportunity o) {

        return o.getOpportunityBatteries().stream()
                .filter(ob -> ob.getBattery() != null)
                .map(ob -> ob.getBattery().getName() + " x" + ob.getQuantity())
                .collect(java.util.stream.Collectors.joining("; "));
    }

    // Validates the agreed workflow rules against the requested state and
    // writes the stage-dependent fields onto `opportunity` (nothing is
    // persisted here). Throws IllegalArgumentException (HTTP 400, message
    // shown to the user) when a rule is broken. A missing follow-up is
    // deliberately NOT a rule - it only produces a warning (noNextAction).
    private void applyWorkflowRules(
            Opportunity opportunity, OpportunityRequest request, SalesStage newStage, boolean converting) {

        // Everything below keys off the stage's stable type, never its
        // (renamable) display name.
        SalesStage previous = opportunity.getSalesStage();
        SalesStageType newType = newStage.getStageType();

        boolean stageChanged = converting
                || previous == null
                || !Objects.equals(previous.getId(), newStage.getId());

        // A deactivated stage keeps working for opportunities already in it,
        // but cannot be newly selected.
        if (stageChanged && Boolean.FALSE.equals(newStage.getIsActive())) {
            throw new IllegalArgumentException(
                    "Sales stage \"" + newStage.getName() + "\" is inactive and cannot be selected.");
        }

        // QUOTATION SENT / NEGOTIATION are sub-statuses of IN PROGRESS now.
        // Historical Opportunities already in those stages keep them
        // (stageChanged is false when they are re-saved as-is), but nobody
        // can newly move an Opportunity into them.
        if ((newType == SalesStageType.LEGACY_QUOTATION_SENT || newType == SalesStageType.LEGACY_NEGOTIATION)
                && stageChanged) {
            throw new IllegalArgumentException(
                    "\"" + newStage.getName() + "\" is now a sub-status of In Progress. "
                            + "Select the In Progress stage and choose it as the sub-status.");
        }

        // Expected closing date: always required to convert a lead, and for
        // any active opportunity. WON/LOST/POSTPONED (and the other closed
        // stages) may be saved without one, which also keeps historical
        // records without a date editable.
        LocalDate closingDate = request.getExpectedClosingDate();

        if (closingDate == null && (converting || OpportunityWorkflow.isActive(newStage))) {
            throw new IllegalArgumentException(converting
                    ? "Expected closing date is required to convert a lead into an opportunity (an estimate is fine)."
                    : "Expected closing date is required for an active opportunity (an estimate is fine).");
        }

        // A changed closing date on an active opportunity needs a reason, so
        // repeated slippage is explained (and visible in the history). Not
        // asked for the first date, for conversion, or for WON/LOST/POSTPONED.
        LocalDate previousClosing = converting ? null : opportunity.getExpectedClosingDate();

        if (previousClosing != null && closingDate != null && !previousClosing.equals(closingDate)
                && OpportunityWorkflow.isActive(newStage)
                && trimToNull(request.getClosingDateChangeReason()) == null) {
            throw new IllegalArgumentException("Please enter the reason for changing the expected closing date.");
        }

        // Sub-status only exists under IN PROGRESS.
        boolean inProgress = newType == SalesStageType.IN_PROGRESS;

        if (request.getSubStatus() != null && !inProgress) {
            throw new IllegalArgumentException(
                    "Sub-status (Quotation Sent / Negotiation) can only be set when the stage is In Progress.");
        }

        // LOST needs a free-text reason when the opportunity becomes LOST.
        // A historical LOST opportunity that never had one can still be
        // re-saved without inventing a reason.
        boolean lost = newType == SalesStageType.LOST;
        String lostReason = trimToNull(request.getLostReason());

        if (lost) {

            boolean wasLost = !converting && OpportunityWorkflow.is(previous, SalesStageType.LOST);
            boolean hadReason = trimToNull(opportunity.getLostReason()) != null;

            if (lostReason == null && (!wasLost || hadReason)) {
                throw new IllegalArgumentException("Please enter the reason this opportunity was lost.");
            }
        }

        // POSTPONED: review date is optional. A new/changed review date
        // re-arms the reminder (clears the acknowledgement).
        boolean postponed = newType == SalesStageType.POSTPONED;
        LocalDate reviewDate = postponed ? request.getPostponedReviewDate() : null;

        boolean reviewDateChanged = !Objects.equals(reviewDate, opportunity.getPostponedReviewDate());

        opportunity.setSalesStage(newStage);
        opportunity.setExpectedClosingDate(closingDate);
        opportunity.setSubStatus(inProgress ? request.getSubStatus() : null);
        opportunity.setLostReason(lost ? lostReason : null);
        opportunity.setPostponedReviewDate(reviewDate);
        opportunity.setPostponedReason(postponed ? trimToNull(request.getPostponedReason()) : null);

        if (!postponed || stageChanged || reviewDateChanged) {
            opportunity.setPostponedReminderAcknowledgedFor(null);
        }
    }

    // Value snapshot of the business fields the history tracks, taken
    // before and after a change so only real differences are logged.
    private record Tracked(
            String title, String value, String validity, String stage, String subStatus,
            String closingDate, String lostReason, String reviewDate, String ackFor, String postponedReason,
            String contactPerson, String contactPhone, String contactEmail, String products, String batteries) {

        static Tracked of(Opportunity o) {

            return new Tracked(
                    o.getTitle(),
                    o.getProductValue() == null ? null
                            : java.math.BigDecimal.valueOf(o.getProductValue()).stripTrailingZeros().toPlainString(),
                    o.getLeadValidity() == null ? null : o.getLeadValidity().name(),
                    o.getSalesStage() == null ? null : o.getSalesStage().getName(),
                    o.getSubStatus() == null ? null : o.getSubStatus().name(),
                    o.getExpectedClosingDate() == null ? null : o.getExpectedClosingDate().toString(),
                    o.getLostReason(),
                    o.getPostponedReviewDate() == null ? null : o.getPostponedReviewDate().toString(),
                    o.getPostponedReminderAcknowledgedFor() == null ? null
                            : o.getPostponedReminderAcknowledgedFor().toString(),
                    o.getPostponedReason(),
                    o.getContactPerson(),
                    o.getContactPhone(),
                    o.getContactEmail(),
                    trimToNull(describeProducts(o)),
                    trimToNull(describeBatteries(o))
            );
        }
    }

    // Writes one history row per field that actually changed. Called only
    // after the Opportunity was saved, so history never records a change
    // that did not persist. Returns how many rows were written.
    private int logChanges(Employee actor, Opportunity opportunity, Tracked before, Tracked after) {

        int written = 0;

        written += logChange(actor, opportunity, "Title", "title", before.title(), after.title());
        written += logChange(actor, opportunity, "Value", "productValue", before.value(), after.value());
        written += logChange(actor, opportunity, "Validity", "leadValidity", before.validity(), after.validity());
        written += logChange(actor, opportunity, "Stage", "salesStage", before.stage(), after.stage());
        written += logChange(actor, opportunity, "Sub-status", "subStatus", before.subStatus(), after.subStatus());
        written += logChange(actor, opportunity, "Expected closing date", "expectedClosingDate",
                before.closingDate(), after.closingDate());
        written += logChange(actor, opportunity, "Lost reason", "lostReason", before.lostReason(), after.lostReason());
        written += logChange(actor, opportunity, "Postponed review date", "postponedReviewDate",
                before.reviewDate(), after.reviewDate());
        written += logChange(actor, opportunity, "Postponed reason", "postponedReason",
                before.postponedReason(), after.postponedReason());
        written += logChange(actor, opportunity, "Postponed reminder acknowledged for review date",
                "postponedReminderAcknowledgedFor", before.ackFor(), after.ackFor());
        written += logChange(actor, opportunity, "Contact person", "contactPerson",
                before.contactPerson(), after.contactPerson());
        written += logChange(actor, opportunity, "Contact phone", "contactPhone",
                before.contactPhone(), after.contactPhone());
        written += logChange(actor, opportunity, "Contact email", "contactEmail",
                before.contactEmail(), after.contactEmail());
        written += logChange(actor, opportunity, "Products", "products", before.products(), after.products());
        written += logChange(actor, opportunity, "Batteries", "batteries", before.batteries(), after.batteries());

        return written;
    }

    private int logChange(Employee actor, Opportunity opportunity, String label, String field,
                          String oldValue, String newValue) {

        if (Objects.equals(oldValue, newValue)) {
            return 0;
        }

        activityLogService.logFieldChange(
                actor, ActivityModule.OPPORTUNITY,
                opportunity.getId(), opportunity.getTitle(),
                label, field, oldValue, newValue
        );

        return 1;
    }

    // Marks the given opportunities for the response:
    //  - hasNextAction: a QUALIFYING follow-up exists on the opportunity or its
    //    originating lead (pending, scheduled now or later, with a described
    //    next action - see FollowUpRules). Computed for every opportunity.
    //  - noNextAction: the opportunity is ACTIVE and has none. This is the
    //    state the save-time rule rejects and the UI explains.
    // Two queries for the whole batch (no per-row lookups).
    void decorateNextAction(Collection<Opportunity> opportunities) {

        List<Opportunity> withIds = opportunities.stream().filter(o -> o.getId() != null).toList();

        for (Opportunity o : opportunities) {
            if (o.getId() == null) {
                o.setHasNextAction(false);
                o.setNoNextAction(false);
            }
        }

        if (withIds.isEmpty()) {
            return;
        }

        LocalDateTime now = BusinessClock.now();

        List<Long> opportunityIds = withIds.stream().map(Opportunity::getId).toList();
        List<Long> leadIds = withIds.stream()
                .map(o -> o.getLead() == null ? null : o.getLead().getId())
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        List<Object[]> opportunityRows = followUpRepository.findPendingFutureByOpportunityIds(opportunityIds, now);
        List<Object[]> leadRows = leadIds.isEmpty() ? List.of()
                : followUpRepository.findPendingFutureByLeadIds(leadIds, now);

        Set<Long> opportunitiesWithFollowUp = qualifyingParentIds(opportunityRows);
        Set<Long> leadsWithFollowUp = qualifyingParentIds(leadRows);

        Map<Long, LocalDateTime> opportunityNext = earliestByParent(opportunityRows);
        Map<Long, LocalDateTime> leadNext = earliestByParent(leadRows);

        for (Opportunity o : withIds) {

            Long leadId = o.getLead() == null ? null : o.getLead().getId();

            boolean has = opportunitiesWithFollowUp.contains(o.getId())
                    || (leadId != null && leadsWithFollowUp.contains(leadId));

            o.setHasNextAction(has);
            o.setNoNextAction(OpportunityWorkflow.isActive(o) && !has);
            o.setNextFollowUpDate(earlier(opportunityNext.get(o.getId()), leadId == null ? null : leadNext.get(leadId)));
        }
    }

    // Same decoration for an export-sized list, in chunks so no IN (...)
    // list grows past what the database accepts.
    private void decorateNextActionInChunks(List<Opportunity> opportunities) {

        for (int i = 0; i < opportunities.size(); i += 1000) {
            decorateNextAction(opportunities.subList(i, Math.min(opportunities.size(), i + 1000)));
        }
    }

    // Earliest scheduled date per parent id, from [parent id, remarks, scheduled date] rows.
    private static Map<Long, LocalDateTime> earliestByParent(List<Object[]> rows) {

        Map<Long, LocalDateTime> earliest = new java.util.HashMap<>();

        for (Object[] row : rows) {

            if (row.length > 2 && row[2] instanceof LocalDateTime scheduled) {
                earliest.merge((Long) row[0], scheduled, (a, b) -> a.isBefore(b) ? a : b);
            }
        }

        return earliest;
    }

    private static LocalDateTime earlier(LocalDateTime a, LocalDateTime b) {

        if (a == null) {
            return b;
        }

        return b == null || a.isBefore(b) ? a : b;
    }

    // Ids (from [parent id, remarks] rows) whose follow-up has a described
    // next action - the description part of FollowUpRules.
    private static Set<Long> qualifyingParentIds(List<Object[]> rows) {

        Set<Long> ids = new HashSet<>();

        for (Object[] row : rows) {

            if (FollowUpRules.hasDescription((String) row[1])) {
                ids.add((Long) row[0]);
            }
        }

        return ids;
    }

    // ---- mandatory next action for ACTIVE opportunities ----

    // The next action a user supplies together with an opportunity save, so
    // the follow-up and the opportunity are created in ONE transaction.
    private record NextActionInput(String description, LocalDateTime scheduledFor, Long activityTypeId) {

        boolean isEmpty() {
            return (description == null || description.isBlank())
                    && scheduledFor == null
                    && activityTypeId == null;
        }
    }

    private static NextActionInput nextActionOf(OpportunityRequest request) {

        return request == null ? null : new NextActionInput(
                request.getNextActionDescription(),
                request.getNextFollowUpDate(),
                request.getNextActionActivityTypeId());
    }

    private static NextActionInput nextActionOf(ReturnToInProgressRequest request) {

        return request == null ? null : new NextActionInput(
                request.getNextActionDescription(),
                request.getNextFollowUpDate(),
                request.getNextActionActivityTypeId());
    }

    // True when a QUALIFYING follow-up already exists on the opportunity (if it
    // exists yet) or on its originating lead.
    private boolean hasQualifyingFollowUp(Long opportunityId, Long leadId) {

        LocalDateTime now = BusinessClock.now();

        if (opportunityId != null && !qualifyingParentIds(
                followUpRepository.findPendingFutureByOpportunityIds(List.of(opportunityId), now)).isEmpty()) {
            return true;
        }

        return leadId != null && !qualifyingParentIds(
                followUpRepository.findPendingFutureByLeadIds(List.of(leadId), now)).isEmpty();
    }

    // Enforces the rule: an opportunity that is (becoming) ACTIVE must have a
    // qualifying pending follow-up - either one that already exists, or the
    // next action supplied right now (returned so the caller creates it after
    // saving the opportunity, in the same transaction). Nothing is invented:
    // without one of the two the save is rejected with an actionable message.
    // Not applied to WON / LOST / POSTPONED or the historical closed stages.
    // Throws before anything is changed.
    private NextActionInput requireNextAction(
            Long opportunityId, Lead lead, SalesStage newStage, NextActionInput supplied) {

        if (!OpportunityWorkflow.isActive(newStage)) {
            return null;
        }

        NextActionInput input = supplied == null || supplied.isEmpty() ? null : supplied;

        if (input != null) {

            if (input.description() == null || !FollowUpRules.hasDescription(input.description())) {
                throw new IllegalArgumentException(FollowUpRules.DESCRIPTION_REQUIRED_MESSAGE);
            }

            if (input.scheduledFor() == null || input.activityTypeId() == null) {
                throw new IllegalArgumentException(
                        "Please give the next action together with its follow-up date and activity type.");
            }

            if (input.scheduledFor().isBefore(BusinessClock.now())) {
                throw new IllegalArgumentException("The follow-up date must be now or in the future.");
            }

            return input;
        }

        if (!hasQualifyingFollowUp(opportunityId, lead == null ? null : lead.getId())) {
            throw new IllegalArgumentException(FollowUpRules.MISSING_MESSAGE);
        }

        return null;
    }

    // Creates the follow-up for the supplied next action through the existing
    // FollowUp service (same permission check, history entry and model as a
    // follow-up scheduled from the Follow Ups screen), owned by the
    // opportunity's responsible employee. Runs inside the caller's transaction.
    private void createNextActionFollowUp(Opportunity opportunity, NextActionInput input) {

        if (input == null) {
            return;
        }

        FollowUpRequest request = new FollowUpRequest();
        request.setOpportunityId(opportunity.getId());
        request.setEmployeeId(opportunity.getLead().getAssignedEmployee().getId());
        request.setActivityTypeId(input.activityTypeId());
        request.setStatus(FollowUpStatus.PENDING);
        request.setScheduledDate(input.scheduledFor());
        request.setRemarks(input.description().trim());

        followUpService.createFollowUp(request);
    }

    // Reusable list query backing the main /api/opportunities endpoint and
    // every stage-bucket endpoint (postponed/dropped/lost/unresponsive/
    // invalid force criteria.stageName; in-progress uses the exclusion
    // list instead via searchInProgress below).
    public Page<Opportunity> searchOpportunities(
            OpportunitySearchCriteria criteria, int page, int size, String sortBy, String sortDir) {

        Employee currentEmployee = currentUserService.getCurrentEmployee();
        List<Long> employeeIds = accessControlService.resolveVisibleEmployeeIds(currentEmployee, OPPORTUNITY_VIEW);

        Specification<Opportunity> spec = buildSpecification(criteria, employeeIds);
        Sort sort = SortWhitelist.resolve(sortBy, sortDir, SORT_FIELDS, "createdDate");

        Page<Opportunity> result = opportunityRepository.findAll(spec, PageRequest.of(page, size, sort));
        decorateNextAction(result.getContent());
        return result;
    }

    public Page<Opportunity> searchInProgressOpportunities(
            OpportunitySearchCriteria criteria, int page, int size, String sortBy, String sortDir) {

        // Same query as every other list, restricted to active stages - shared
        // with /export (activeOnly) so the In Progress export matches this list.
        criteria.setActiveOnly(true);

        return searchOpportunities(criteria, page, size, sortBy, sortDir);
    }

    // Backward-compatible overload (explicit ids only, no exclusions).
    public List<Opportunity> findForExport(
            OpportunitySearchCriteria criteria, List<Long> ids, String sortBy, String sortDir) {
        return findForExport(criteria, ids, null, sortBy, sortDir);
    }

    // The rows of an export, chosen exactly like the list: the caller's RBAC
    // scope AND the active filters, then
    //  - `ids` given: only those rows ("export selected" on one page), or
    //  - otherwise every matching row ("export all matching", or "select all
    //    N matching records") minus `excludeIds` (rows the user unticked).
    // Neither list can widen the result: both are AND-ed onto the scoped,
    // filtered query, so an id outside it is simply ignored. More matching
    // rows than EXPORT_MAX_ROWS is rejected instead of silently truncated.
    public List<Opportunity> findForExport(
            OpportunitySearchCriteria criteria, List<Long> ids, List<Long> excludeIds,
            String sortBy, String sortDir) {

        Employee currentEmployee = currentUserService.getCurrentEmployee();
        // Requires OPPORTUNITY_EXPORT and applies the narrower of the export
        // and view scopes.
        List<Long> employeeIds = accessControlService.resolveExportEmployeeIds(
                currentEmployee, OPPORTUNITY_VIEW, OPPORTUNITY_EXPORT);

        Specification<Opportunity> spec = SpecificationUtil.and(java.util.Arrays.asList(
                buildSpecification(criteria, employeeIds),
                ids != null && !ids.isEmpty() ? OpportunitySpecifications.hasIds(ids) : null,
                OpportunitySpecifications.notInIds(excludeIds)
        ));

        long matching = opportunityRepository.count(spec);

        if (matching > EXPORT_MAX_ROWS) {
            throw new IllegalArgumentException("This export would contain " + matching
                    + " opportunities; at most " + EXPORT_MAX_ROWS
                    + " can be exported at once. Please narrow the filters.");
        }

        Sort sort = SortWhitelist.resolve(sortBy, sortDir, SORT_FIELDS, "createdDate");
        Pageable pageable = PageRequest.of(0, EXPORT_MAX_ROWS, sort);

        List<Opportunity> opportunities = opportunityRepository.findAll(spec, pageable).getContent();

        // Line items for every exported row in a couple of queries per 1,000
        // rows (instead of one lazy load per 50 rows per collection), loaded
        // into the same persistence context the rows are already in.
        List<Long> exportedIds = opportunities.stream().map(Opportunity::getId).toList();

        for (int i = 0; i < exportedIds.size(); i += 1000) {
            List<Long> chunk = exportedIds.subList(i, Math.min(exportedIds.size(), i + 1000));
            opportunityRepository.fetchProducts(chunk);
            opportunityRepository.fetchBatteries(chunk);
        }

        decorateNextActionInChunks(opportunities);

        activityLogService.log(
                currentEmployee,
                ActivityModule.OPPORTUNITY, ActivityAction.EXPORT,
                null, null,
                "Exported " + opportunities.size() + " opportunity(ies)"
        );

        return opportunities;
    }

    @Transactional
    public BulkOperationResult bulkDeleteOpportunities(List<Long> ids) {

        Employee currentEmployee = currentUserService.getCurrentEmployee();
        accessControlService.requirePermission(currentEmployee, OPPORTUNITY_DELETE);

        List<Long> succeeded = new ArrayList<>();
        List<Long> skipped = new ArrayList<>();

        for (Long id : ids) {

            Optional<Opportunity> opportunityOpt = opportunityRepository.findById(id);

            if (opportunityOpt.isEmpty()) {
                skipped.add(id);
                continue;
            }

            Opportunity opportunity = opportunityOpt.get();

            try {

                accessControlService.assertCanAccess(
                        currentEmployee, OPPORTUNITY_DELETE, opportunity.getLead().getAssignedEmployee().getId());

                cascadeDeleteOpportunity(opportunity, currentEmployee);
                succeeded.add(id);

            } catch (AccessDeniedException e) {
                skipped.add(id);
            }
        }

        return new BulkOperationResult(succeeded, skipped);
    }

    private Specification<Opportunity> buildSpecification(OpportunitySearchCriteria criteria, List<Long> employeeIds) {

        LocalDateTime createdFrom = criteria.getCreatedFrom() != null
                ? criteria.getCreatedFrom().atStartOfDay() : null;

        LocalDateTime createdTo = criteria.getCreatedTo() != null
                ? criteria.getCreatedTo().plusDays(1).atStartOfDay() : null;

        return SpecificationUtil.and(java.util.Arrays.asList(
                OpportunitySpecifications.fetchAssociations(),
                OpportunitySpecifications.ownerIn(employeeIds),
                Boolean.TRUE.equals(criteria.getActiveOnly()) ? OpportunitySpecifications.activeStage() : null,
                OpportunitySpecifications.search(criteria.getSearch()),
                OpportunitySpecifications.hasStageName(criteria.getStageName()),
                OpportunitySpecifications.hasStageType(criteria.getStageType()),
                OpportunitySpecifications.hasSalesStageId(criteria.getSalesStageId()),
                OpportunitySpecifications.hasAssignedEmployeeId(criteria.getAssignedEmployeeId()),
                OpportunitySpecifications.hasIndustryId(criteria.getIndustryId()),
                OpportunitySpecifications.hasProductId(criteria.getProductId()),
                OpportunitySpecifications.hasBatteryId(criteria.getBatteryId()),
                OpportunitySpecifications.hasLeadValidity(criteria.getLeadValidity()),
                OpportunitySpecifications.createdBetween(createdFrom, createdTo),
                OpportunitySpecifications.expectedClosingBetween(
                        criteria.getExpectedClosingFrom(), criteria.getExpectedClosingTo()),
                OpportunitySpecifications.valueBetween(criteria.getMinValue(), criteria.getMaxValue())
        ));
    }

    public Opportunity getOpportunityById(Long id) {

        Opportunity opportunity = getAuthorizedOpportunity(id, OPPORTUNITY_VIEW);

        activityLogService.log(
                currentUserService.getCurrentEmployee(),
                ActivityModule.OPPORTUNITY, ActivityAction.VIEW,
                opportunity.getId(), opportunity.getTitle(),
                "Viewed opportunity"
        );

        decorateNextAction(List.of(opportunity));

        return opportunity;
    }

    // Transactional: validation failures roll everything back, and a next-action
    // follow-up supplied with the edit is saved together with the opportunity.
    @Transactional
    public Opportunity updateOpportunity(Long id, OpportunityRequest request) {

        Opportunity opportunity = getAuthorizedOpportunity(id, OPPORTUNITY_MANAGE);

        // Existing stage must be used as-is - an edit never creates a stage
        // (stage configuration is Admin-only).
        SalesStage salesStage = resolveStage(request.getSalesStage());

        // A WON opportunity that already became a Customer cannot be moved
        // out of WON (it would orphan the Customer link). Decided by stage
        // type, so renaming WON neither weakens nor triggers this.
        boolean isLeavingWon = OpportunityWorkflow.is(opportunity.getSalesStage(), SalesStageType.WON)
                && salesStage.getStageType() != SalesStageType.WON;

        if (isLeavingWon && customerRepository.findByOpportunity(opportunity).isPresent()) {
            throw new IllegalArgumentException(WON_LOCK_MESSAGE);
        }

        // An opportunity that is (or is becoming) ACTIVE must have a qualifying
        // pending follow-up - existing, or supplied with this edit. Checked
        // before anything on the entity is touched. Not asked for WON / LOST /
        // POSTPONED, so a legacy record lacking one can still be closed out,
        // and the edit form can supply the missing one to fix it.
        NextActionInput nextAction = requireNextAction(
                opportunity.getId(), opportunity.getLead(), salesStage, nextActionOf(request));

        // Contact details / products / batteries: validated now, written
        // after the workflow rules. Only the Opportunity is changed - never
        // its Lead, which keeps the values it was converted with.
        DetailsEdit details = resolveDetailsEdit(opportunity, request);

        Tracked before = Tracked.of(opportunity);

        // Workflow rules validate first (and throw before anything on the
        // entity is touched), then write the stage-dependent fields.
        applyWorkflowRules(opportunity, request, salesStage, false);

        applyDetailsEdit(opportunity, details);

        opportunity.setTitle(request.getTitle());
        opportunity.setProductValue(request.getProductValue());
        opportunity.setLeadValidity(request.getLeadValidity());

        opportunity.getLead().setLeadValidity(request.getLeadValidity());
        leadRepository.save(opportunity.getLead());

        Opportunity saved = opportunityRepository.save(opportunity);

        createNextActionFollowUp(saved, nextAction);

        Employee actor = currentUserService.getCurrentEmployee();

        // Field-level history, written only now that the save succeeded.
        // A save that changed nothing keeps the original generic entry.
        int changes = logChanges(actor, saved, before, Tracked.of(saved));

        // Why the closing date moved (required by applyWorkflowRules when an
        // existing date of an active opportunity changes) - kept in the
        // history next to the date change itself.
        String dateReason = trimToNull(request.getClosingDateChangeReason());

        if (dateReason != null
                && before.closingDate() != null
                && saved.getExpectedClosingDate() != null
                && !before.closingDate().equals(saved.getExpectedClosingDate().toString())) {

            changes += logChange(actor, saved, "Closing date change reason", "closingDateChangeReason",
                    null, dateReason);
        }

        if (changes == 0) {

            activityLogService.log(
                    actor,
                    ActivityModule.OPPORTUNITY, ActivityAction.UPDATE,
                    saved.getId(), saved.getTitle(),
                    "Updated opportunity"
            );
        }

        decorateNextAction(List.of(saved));

        return saved;
    }

    // Explicit "OK" on a postponed review-date reminder. Records that the
    // reminder for the current review date was seen; the stage is NOT
    // touched - returning to IN PROGRESS is a separate, deliberate action
    // (returnToInProgress).
    public Opportunity acknowledgePostponedReminder(Long id) {

        Opportunity opportunity = getAuthorizedOpportunity(id, OPPORTUNITY_MANAGE);

        if (!OpportunityWorkflow.is(opportunity.getSalesStage(), SalesStageType.POSTPONED)
                || opportunity.getPostponedReviewDate() == null) {
            throw new IllegalArgumentException(
                    "Only a postponed opportunity with a review date has a reminder to acknowledge.");
        }

        LocalDate reviewDate = opportunity.getPostponedReviewDate();
        LocalDate previouslyAcknowledged = opportunity.getPostponedReminderAcknowledgedFor();

        // Atomic: returns 0 when this date was already acknowledged (a repeat
        // click, or a concurrent request that got there first) - in which
        // case nothing changes and no second history entry is written.
        int updated = opportunityRepository.acknowledgeReminder(id, reviewDate, LocalDateTime.now());

        Opportunity saved = opportunityRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Opportunity not found"));

        if (updated > 0) {

            logChange(currentUserService.getCurrentEmployee(), saved,
                    "Postponed reminder acknowledged for review date", "postponedReminderAcknowledgedFor",
                    previouslyAcknowledged == null ? null : previouslyAcknowledged.toString(),
                    reviewDate.toString());
        }

        decorateNextAction(List.of(saved));

        return saved;
    }

    // Explicit "Return to In Progress" for a POSTPONED opportunity. The
    // only way, besides a normal edit, that the stage moves on from
    // POSTPONED; acknowledging a reminder never does this.
    @Transactional
    public Opportunity returnToInProgress(Long id, ReturnToInProgressRequest request) {

        Opportunity opportunity = getAuthorizedOpportunity(id, OPPORTUNITY_MANAGE);

        if (!OpportunityWorkflow.is(opportunity.getSalesStage(), SalesStageType.POSTPONED)) {
            throw new IllegalArgumentException("Only a postponed opportunity can be returned to In Progress.");
        }

        LocalDate closingDate = request != null && request.getExpectedClosingDate() != null
                ? request.getExpectedClosingDate()
                : opportunity.getExpectedClosingDate();

        if (closingDate == null) {
            throw new IllegalArgumentException(
                    "Expected closing date is required for an active opportunity (an estimate is fine).");
        }

        SalesStage inProgress = salesStageService.getByType(SalesStageType.IN_PROGRESS);

        // Resuming makes it ACTIVE again, so it needs a qualifying pending
        // follow-up: one that already exists, or the next action supplied here.
        NextActionInput nextAction = requireNextAction(
                opportunity.getId(), opportunity.getLead(), inProgress, nextActionOf(request));

        Tracked before = Tracked.of(opportunity);

        opportunity.setSalesStage(inProgress);
        opportunity.setSubStatus(null);
        opportunity.setExpectedClosingDate(closingDate);
        opportunity.setPostponedReviewDate(null);
        opportunity.setPostponedReason(null);
        opportunity.setPostponedReminderAcknowledgedFor(null);

        Opportunity saved = opportunityRepository.save(opportunity);

        createNextActionFollowUp(saved, nextAction);

        logChanges(currentUserService.getCurrentEmployee(), saved, before, Tracked.of(saved));

        decorateNextAction(List.of(saved));

        return saved;
    }

    // Non-blocking dashboard reminders over the Opportunities the caller can
    // view: expired closing dates, active opportunities with no upcoming
    // follow-up, and postponed opportunities whose review date has arrived.
    // Read-only - nothing here changes any Opportunity.
    public WorkflowAlertsResponse getWorkflowAlerts() {

        Employee currentEmployee = currentUserService.getCurrentEmployee();
        List<Long> employeeIds = accessControlService.resolveVisibleEmployeeIds(currentEmployee, OPPORTUNITY_VIEW);

        LocalDate today = BusinessClock.today();
        LocalDateTime now = BusinessClock.now();

        Specification<Opportunity> owner = OpportunitySpecifications.ownerIn(employeeIds);

        Specification<Opportunity> expired = SpecificationUtil.and(java.util.Arrays.asList(
                owner, OpportunitySpecifications.closingDateExpired(today)));

        Specification<Opportunity> noNextAction = SpecificationUtil.and(java.util.Arrays.asList(
                owner, OpportunitySpecifications.activeStage(),
                OpportunitySpecifications.noQualifyingFollowUp(now)));

        Specification<Opportunity> postponedDue = SpecificationUtil.and(java.util.Arrays.asList(
                owner, OpportunitySpecifications.postponedReminderDue(today)));

        long expiredCount = opportunityRepository.count(expired);
        long noNextActionCount = opportunityRepository.count(noNextAction);
        long postponedCount = opportunityRepository.count(postponedDue);

        return new WorkflowAlertsResponse(
                expiredCount,
                alertItems(expired, "expectedClosingDate"),
                noNextActionCount,
                alertItems(noNextAction, "expectedClosingDate"),
                postponedCount,
                alertItems(postponedDue, "postponedReviewDate")
        );
    }

    private List<WorkflowAlertsResponse.Item> alertItems(Specification<Opportunity> spec, String sortField) {

        return opportunityRepository
                .findAll(spec, PageRequest.of(0, ALERT_SAMPLE_SIZE, Sort.by(Sort.Direction.ASC, sortField)))
                .getContent()
                .stream()
                .map(o -> new WorkflowAlertsResponse.Item(
                        o.getId(),
                        o.getTitle(),
                        o.getLead() == null ? null : o.getLead().getCompanyName(),
                        o.getSalesStage() == null ? null : o.getSalesStage().getName(),
                        o.getExpectedClosingDate(),
                        o.getPostponedReviewDate()))
                .toList();
    }

    @Transactional
    public void deleteOpportunity(Long id) {

        Opportunity opportunity = getAuthorizedOpportunity(id, OPPORTUNITY_DELETE);

        cascadeDeleteOpportunity(opportunity, currentUserService.getCurrentEmployee());
    }

    // Deletes an Opportunity's dependents - its Won Customer conversion (if
    // any) and every FollowUp attached to it - then the Opportunity itself,
    // in that order, so no foreign-key constraint on either child table is
    // ever violated. Runs inside the caller's transaction (deleteOpportunity
    // above, or LeadService.deleteLead cascading one level further down the
    // Lead -> Opportunity -> Customer/FollowUps chain) so a failure at any
    // step rolls the whole delete back - no partially-deleted chain.
    //
    // Deliberately package-private and takes an already-authorized
    // Opportunity + the acting Employee rather than re-running
    // getAuthorizedOpportunity/OPPORTUNITY_DELETE itself: the caller has
    // already made the one authorization decision that matters (can this
    // employee delete this top-level record), and a dependent record being
    // technically outside that same TEAM/OWN scope must never turn a
    // legitimate cascade into an unexpected 403 partway through.
    void cascadeDeleteOpportunity(Opportunity opportunity, Employee actingEmployee) {

        Optional<Customer> customer = customerRepository.findByOpportunity(opportunity);

        if (customer.isPresent()) {

            customerRepository.delete(customer.get());

            activityLogService.log(
                    actingEmployee,
                    ActivityModule.CUSTOMER, ActivityAction.DELETE,
                    customer.get().getId(), customer.get().getCompanyName(),
                    "Deleted customer (cascaded from opportunity deletion)"
            );
        }

        List<FollowUp> followUps = followUpRepository.findByOpportunityId(opportunity.getId());

        for (FollowUp followUp : followUps) {

            followUpRepository.delete(followUp);

            activityLogService.log(
                    actingEmployee,
                    ActivityModule.FOLLOWUP, ActivityAction.DELETE,
                    followUp.getId(), opportunity.getTitle(),
                    "Deleted follow-up (cascaded from opportunity deletion)"
            );
        }

        opportunityRepository.delete(opportunity);

        activityLogService.log(
                actingEmployee,
                ActivityModule.OPPORTUNITY, ActivityAction.DELETE,
                opportunity.getId(), opportunity.getTitle(),
                "Deleted opportunity"
        );
    }

    // Small delegation point so LeadService.getDeleteImpact can tell
    // whether a Lead's Opportunity has a Won Customer conversion without
    // LeadService needing its own CustomerRepository dependency - Customer
    // lookups by Opportunity stay owned by this service.
    public boolean hasCustomer(Opportunity opportunity) {
        return customerRepository.findByOpportunity(opportunity).isPresent();
    }

    // Backs GET /api/opportunities/{id}/delete-impact - tells the frontend
    // exactly what deleteOpportunity/cascadeDeleteOpportunity above will
    // remove, before the Admin commits to it.
    public DeleteImpactResponse getDeleteImpact(Long id) {

        Opportunity opportunity = getAuthorizedOpportunity(id, OPPORTUNITY_DELETE);

        boolean hasCustomer = customerRepository.findByOpportunity(opportunity).isPresent();
        int followUpCount = followUpRepository.findByOpportunityId(opportunity.getId()).size();

        return new DeleteImpactResponse(0, hasCustomer ? 1 : 0, followUpCount, hasCustomer);
    }

    private Opportunity getAuthorizedOpportunity(Long id, String permissionCode) {

        Opportunity opportunity = opportunityRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Opportunity not found"));

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        accessControlService.assertCanAccess(
                currentEmployee,
                permissionCode,
                opportunity.getLead().getAssignedEmployee().getId()
        );

        return opportunity;
    }
}