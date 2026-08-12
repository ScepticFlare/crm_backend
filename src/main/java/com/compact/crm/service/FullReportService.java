package com.compact.crm.service;

import com.compact.crm.dto.request.FullReportRequest;
import com.compact.crm.dto.response.*;
import com.compact.crm.entity.*;
import com.compact.crm.enums.LeadValidity;
import com.compact.crm.exception.ResourceNotFoundException;
import com.compact.crm.repository.CustomerRepository;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.OpportunityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FullReportService {

    public static final Set<String> ALL_RECORD_TYPES = Set.of("LEAD", "OPPORTUNITY", "CUSTOMER");

    public static final Set<String> ALL_SECTIONS = Set.of(
            "LEAD_INFO", "OPPORTUNITY_INFO", "CUSTOMER_INFO",
            "PRODUCT_INFO", "BATTERY_INFO", "EMPLOYEE_INFO"
    );

    private final LeadRepository leadRepository;
    private final OpportunityRepository opportunityRepository;
    private final CustomerRepository customerRepository;
    private final EmployeeRepository employeeRepository;

    @Transactional(readOnly = true)
    public FullReportResponse generateFullReport(FullReportRequest request) {

        Set<String> recordTypes = normalizedRecordTypes(request.getRecordTypes());

        LocalDateTime from = request.getFrom().atStartOfDay();
        LocalDateTime to = request.getTo().plusDays(1).atStartOfDay().minusSeconds(1);

        Employee employee = request.getEmployeeId() != null
                ? employeeRepository.findById(request.getEmployeeId())
                        .orElseThrow(() -> new ResourceNotFoundException("Employee not found"))
                : null;

        List<Lead> leads = new ArrayList<>();
        List<Opportunity> opportunities = new ArrayList<>();
        List<Customer> customers = new ArrayList<>();

        if (recordTypes.contains("LEAD")) {

            leads = leadRepository.findForFullReportWithProducts(
                    from, to, employee,
                    request.getLeadSourceId(), request.getIndustryId(),
                    request.getLeadValidity(),
                    request.getProductId(), request.getBatteryId()
            );

            // second pass fetch-joins leadBatteries onto the same managed
            // Lead entities already loaded above (same persistence context)
            leadRepository.findForFullReportWithBatteries(
                    from, to, employee,
                    request.getLeadSourceId(), request.getIndustryId(),
                    request.getLeadValidity(),
                    request.getProductId(), request.getBatteryId()
            );

            leads.sort(Comparator.comparing(Lead::getCreatedAt));
        }

        if (recordTypes.contains("OPPORTUNITY")) {

            opportunities = opportunityRepository.findForFullReportWithProducts(
                    from, to, employee,
                    request.getLeadSourceId(), request.getIndustryId(),
                    request.getSalesStageId(), request.getLeadValidity(),
                    request.getProductId(), request.getBatteryId()
            );

            opportunityRepository.findForFullReportWithBatteries(
                    from, to, employee,
                    request.getLeadSourceId(), request.getIndustryId(),
                    request.getSalesStageId(), request.getLeadValidity(),
                    request.getProductId(), request.getBatteryId()
            );

            opportunities.sort(Comparator.comparing(Opportunity::getCreatedAt));
        }

        if (recordTypes.contains("CUSTOMER")) {

            customers = customerRepository.findForFullReportWithProducts(
                    from, to, employee,
                    request.getLeadSourceId(), request.getIndustryId(),
                    request.getSalesStageId(),
                    request.getProductId(), request.getBatteryId()
            );

            customerRepository.findForFullReportWithBatteries(
                    from, to, employee,
                    request.getLeadSourceId(), request.getIndustryId(),
                    request.getSalesStageId(),
                    request.getProductId(), request.getBatteryId()
            );

            customers.sort(Comparator.comparing(Customer::getCreatedAt));
        }

        // Deduplicated set of Leads actually represented in this report,
        // regardless of which record type(s) surfaced them - used so
        // product/battery totals are never double-counted.
        Map<Long, Lead> leadsInScope = new LinkedHashMap<>();

        leads.forEach(l -> leadsInScope.put(l.getId(), l));

        opportunities.stream()
                .map(Opportunity::getLead)
                .filter(Objects::nonNull)
                .forEach(l -> leadsInScope.put(l.getId(), l));

        customers.stream()
                .map(Customer::getOpportunity)
                .filter(Objects::nonNull)
                .map(Opportunity::getLead)
                .filter(Objects::nonNull)
                .forEach(l -> leadsInScope.put(l.getId(), l));

        FullReportSummary summary = buildSummary(leads, opportunities, customers, leadsInScope.values());

        return FullReportResponse.builder()
                .summary(summary)
                .leads(leads.stream().map(this::toLeadRow).toList())
                .opportunities(opportunities.stream().map(this::toOpportunityRow).toList())
                .customers(customers.stream().map(this::toCustomerRow).toList())
                .productLineItems(buildProductLineItems(leadsInScope.values()))
                .batteryLineItems(buildBatteryLineItems(leadsInScope.values()))
                .build();
    }

    public static Set<String> normalizedRecordTypes(Set<String> recordTypes) {

        if (recordTypes == null || recordTypes.isEmpty()) {
            return ALL_RECORD_TYPES;
        }

        return recordTypes.stream()
                .map(String::toUpperCase)
                .collect(Collectors.toSet());
    }

    public static Set<String> normalizedSections(Set<String> sections) {

        if (sections == null || sections.isEmpty()) {
            return ALL_SECTIONS;
        }

        return sections.stream()
                .map(String::toUpperCase)
                .collect(Collectors.toSet());
    }

    private FullReportSummary buildSummary(
            List<Lead> leads,
            List<Opportunity> opportunities,
            List<Customer> customers,
            Collection<Lead> leadsInScope
    ) {

        long validLeads = leads.stream()
                .filter(l -> l.getLeadValidity() == LeadValidity.VALID)
                .count();

        long invalidLeads = leads.size() - validLeads;

        long won = countByStage(opportunities, "WON");
        long lost = countByStage(opportunities, "LOST");
        long postponed = countByStage(opportunities, "POSTPONED");
        long dropped = countByStage(opportunities, "DROPPED");
        long unresponsive = countByStage(opportunities, "UNRESPONSIVE");
        long inProgress = countInProgress(opportunities);

        Map<String, Long> productTotals = new LinkedHashMap<>();
        Map<String, Long> batteryTotals = new LinkedHashMap<>();

        for (Lead lead : leadsInScope) {

            for (LeadProduct lp : lead.getLeadProducts()) {
                productTotals.merge(lp.getProduct().getName(), (long) lp.getQuantity(), Long::sum);
            }

            for (LeadBattery lb : lead.getLeadBatteries()) {
                batteryTotals.merge(lb.getBattery().getName(), (long) lb.getQuantity(), Long::sum);
            }
        }

        return FullReportSummary.builder()
                .totalLeads(leads.size())
                .validLeads(validLeads)
                .invalidLeads(invalidLeads)
                .totalOpportunities(opportunities.size())
                .won(won)
                .lost(lost)
                .inProgress(inProgress)
                .postponed(postponed)
                .dropped(dropped)
                .unresponsive(unresponsive)
                .totalCustomers(customers.size())
                .productTotals(toItemTotals(productTotals))
                .batteryTotals(toItemTotals(batteryTotals))
                .build();
    }

    private long countByStage(List<Opportunity> opportunities, String stageName) {

        return opportunities.stream()
                .filter(o -> o.getSalesStage() != null
                        && stageName.equalsIgnoreCase(o.getSalesStage().getName()))
                .count();
    }

    // "In Progress" means the opportunity's stage isn't one of the other
    // tracked outcomes - matches OpportunityRepository.searchInProgressOpportunities,
    // which the live In Progress list is built on.
    private static final Set<String> NON_IN_PROGRESS_STAGES =
            Set.of("WON", "LOST", "POSTPONED", "DROPPED", "UNRESPONSIVE");

    private long countInProgress(List<Opportunity> opportunities) {

        return opportunities.stream()
                .filter(o -> o.getSalesStage() != null
                        && !NON_IN_PROGRESS_STAGES.contains(o.getSalesStage().getName().toUpperCase()))
                .count();
    }

    private List<FullReportItemTotal> toItemTotals(Map<String, Long> totals) {

        return totals.entrySet().stream()
                .map(e -> FullReportItemTotal.builder()
                        .name(e.getKey())
                        .totalQuantity(e.getValue())
                        .build())
                .toList();
    }

    private List<FullReportProductLineItem> buildProductLineItems(Collection<Lead> leads) {

        List<FullReportProductLineItem> items = new ArrayList<>();

        for (Lead lead : leads) {

            for (LeadProduct lp : lead.getLeadProducts()) {

                items.add(FullReportProductLineItem.builder()
                        .leadId(lead.getId())
                        .companyName(lead.getCompanyName())
                        .contactPerson(lead.getContactPerson())
                        .productName(lp.getProduct().getName())
                        .quantity(lp.getQuantity())
                        .build());
            }
        }

        return items;
    }

    private List<FullReportBatteryLineItem> buildBatteryLineItems(Collection<Lead> leads) {

        List<FullReportBatteryLineItem> items = new ArrayList<>();

        for (Lead lead : leads) {

            for (LeadBattery lb : lead.getLeadBatteries()) {

                items.add(FullReportBatteryLineItem.builder()
                        .leadId(lead.getId())
                        .companyName(lead.getCompanyName())
                        .contactPerson(lead.getContactPerson())
                        .batteryName(lb.getBattery().getName())
                        .quantity(lb.getQuantity())
                        .build());
            }
        }

        return items;
    }

    private String formatProducts(Lead lead) {

        if (lead.getLeadProducts().isEmpty()) {
            return "";
        }

        return lead.getLeadProducts().stream()
                .map(lp -> lp.getProduct().getName() + " x" + lp.getQuantity())
                .collect(Collectors.joining("; "));
    }

    private String formatBatteries(Lead lead) {

        if (lead.getLeadBatteries().isEmpty()) {
            return "";
        }

        return lead.getLeadBatteries().stream()
                .map(lb -> lb.getBattery().getName() + " x" + lb.getQuantity())
                .collect(Collectors.joining("; "));
    }

    private FullReportLeadRow toLeadRow(Lead lead) {

        Employee emp = lead.getAssignedEmployee();

        return FullReportLeadRow.builder()
                .leadId(lead.getId())
                .companyName(lead.getCompanyName())
                .contactPerson(lead.getContactPerson())
                .designation(lead.getDesignation())
                .phone(lead.getPhone())
                .alternatePhone(lead.getAlternatePhone())
                .email(lead.getEmail())
                .secondaryEmail(lead.getSecondaryEmail())
                .city(lead.getCity())
                .state(lead.getState())
                .pincode(lead.getPincode())
                .industry(lead.getIndustry() != null ? lead.getIndustry().getName() : null)
                .leadSource(lead.getLeadSource() != null ? lead.getLeadSource().getName() : null)
                .leadStatus(lead.getLeadStatus() != null ? lead.getLeadStatus().name() : null)
                .leadValidity(lead.getLeadValidity() != null ? lead.getLeadValidity().name() : null)
                .assignedEmployeeName(emp != null ? emp.getName() : null)
                .assignedEmployeeEmail(emp != null ? emp.getEmail() : null)
                .description(lead.getDescription())
                .finalRemarks(lead.getFinalRemarks())
                .createdAt(lead.getCreatedAt())
                .products(formatProducts(lead))
                .batteries(formatBatteries(lead))
                .build();
    }

    private FullReportOpportunityRow toOpportunityRow(Opportunity opportunity) {

        Lead lead = opportunity.getLead();
        Employee emp = lead != null ? lead.getAssignedEmployee() : null;

        return FullReportOpportunityRow.builder()
                .opportunityId(opportunity.getId())
                .title(opportunity.getTitle())
                .salesStage(opportunity.getSalesStage() != null ? opportunity.getSalesStage().getName() : null)
                .productValue(opportunity.getProductValue())
                .expectedClosingDate(opportunity.getExpectedClosingDate())
                .leadValidity(opportunity.getLeadValidity() != null ? opportunity.getLeadValidity().name() : null)
                .createdAt(opportunity.getCreatedAt())
                .leadId(lead != null ? lead.getId() : null)
                .companyName(lead != null ? lead.getCompanyName() : null)
                .contactPerson(lead != null ? lead.getContactPerson() : null)
                .phone(lead != null ? lead.getPhone() : null)
                .email(lead != null ? lead.getEmail() : null)
                .industry(lead != null && lead.getIndustry() != null ? lead.getIndustry().getName() : null)
                .leadSource(lead != null && lead.getLeadSource() != null ? lead.getLeadSource().getName() : null)
                .assignedEmployeeName(emp != null ? emp.getName() : null)
                .assignedEmployeeEmail(emp != null ? emp.getEmail() : null)
                .products(lead != null ? formatProducts(lead) : "")
                .batteries(lead != null ? formatBatteries(lead) : "")
                .build();
    }

    private FullReportCustomerRow toCustomerRow(Customer customer) {

        Opportunity opportunity = customer.getOpportunity();
        Lead lead = opportunity != null ? opportunity.getLead() : null;
        Employee emp = customer.getAssignedEmployee();

        return FullReportCustomerRow.builder()
                .customerId(customer.getId())
                .customerCode(customer.getCustomerCode())
                .companyName(customer.getCompanyName())
                .contactPerson(customer.getContactPerson())
                .designation(customer.getDesignation())
                .phone(customer.getPhone())
                .alternatePhone(customer.getAlternatePhone())
                .email(customer.getEmail())
                .secondaryEmail(customer.getSecondaryEmail())
                .website(customer.getWebsite())
                .city(customer.getCity())
                .state(customer.getState())
                .pincode(customer.getPincode())
                .billingAddress(customer.getBillingAddress())
                .shippingAddress(customer.getShippingAddress())
                .gstNumber(customer.getGstNumber())
                .assignedEmployeeName(emp != null ? emp.getName() : null)
                .assignedEmployeeEmail(emp != null ? emp.getEmail() : null)
                .createdAt(customer.getCreatedAt())
                .opportunityId(opportunity != null ? opportunity.getId() : null)
                .opportunityTitle(opportunity != null ? opportunity.getTitle() : null)
                .salesStage(opportunity != null && opportunity.getSalesStage() != null
                        ? opportunity.getSalesStage().getName() : null)
                .productValue(opportunity != null ? opportunity.getProductValue() : null)
                .leadId(lead != null ? lead.getId() : null)
                .industry(lead != null && lead.getIndustry() != null ? lead.getIndustry().getName() : null)
                .leadSource(lead != null && lead.getLeadSource() != null ? lead.getLeadSource().getName() : null)
                .products(lead != null ? formatProducts(lead) : "")
                .batteries(lead != null ? formatBatteries(lead) : "")
                .build();
    }
}
