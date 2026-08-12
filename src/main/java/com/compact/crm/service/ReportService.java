package com.compact.crm.service;

import com.compact.crm.dto.response.LeadReportResponse;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.OpportunityRepository;
import com.compact.crm.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.TextStyle;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReportService {

    private final LeadRepository leadRepository;
    private final OpportunityRepository opportunityRepository;
    private final CurrentUserService currentUserService;

    public List<LeadReportResponse> generateLeadReport(
            LocalDate fromDate,
            LocalDate toDate,
            Long leadSourceId
    ) {

        LocalDateTime from = fromDate.atStartOfDay();
        LocalDateTime to = toDate.plusDays(1).atStartOfDay().minusSeconds(1);

        List<Lead> leads;
        List<Opportunity> opportunities;

        if (currentUserService.isAdmin()) {

            leads = leadRepository.findByCreatedAtBetween(from, to);

            opportunities = opportunityRepository.findByCreatedAtBetween(from, to);

        } else {

            Employee employee = currentUserService.getCurrentEmployee();

            leads = leadRepository.findByAssignedEmployeeAndCreatedAtBetween(
                    employee,
                    from,
                    to
            );

            opportunities =
                    opportunityRepository.findByLeadAssignedEmployeeAndCreatedAtBetween(
                            employee,
                            from,
                            to
                    );
        }
        if (leadSourceId != null) {

            leads = leads.stream()
                    .filter(lead ->
                            lead.getLeadSource() != null &&
                                    lead.getLeadSource().getId().equals(leadSourceId))
                    .toList();
        }

        Map<Long, Opportunity> opportunityMap =
                opportunities.stream()
                        .collect(Collectors.toMap(
                                o -> o.getLead().getId(),
                                Function.identity()
                        ));

        Map<String, LeadReportResponse> monthlyReport = new LinkedHashMap<>();

        for (Lead lead : leads) {

            String month =
                    lead.getCreatedAt()
                            .getMonth()
                            .getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                            + " "
                            + lead.getCreatedAt().getYear();

            LeadReportResponse row = monthlyReport.computeIfAbsent(
                    month,
                    m -> LeadReportResponse.builder()
                            .month(m)
                            .totalLeadsReceived(0)
                            .invalidLeads(0)
                            .validLeads(0)
                            .won(0)
                            .lost(0)
                            .inProgress(0)
                            .postponed(0)
                            .dropped(0)
                            .unresponsive(0)
                            .wonConversionRate(0)
                            .build()
            );

            row.setTotalLeadsReceived(row.getTotalLeadsReceived() + 1);

            if (lead.getLeadValidity().name().equalsIgnoreCase("INVALID")) {

                row.setInvalidLeads(row.getInvalidLeads() + 1);
                continue;
            }

            row.setValidLeads(row.getValidLeads() + 1);

            Opportunity opportunity =
                    opportunityMap.get(lead.getId());

            // Primary signal is the linked Opportunity's Sales Stage. When a
            // lead has not been converted into an Opportunity yet, fall back
            // to its own Lead Status so pre-conversion WON/LOST/DROPPED/
            // UNRESPONSIVE leads are still counted correctly.
            String state = opportunity != null
                    ? opportunity.getSalesStage().getName().toUpperCase()
                    : lead.getLeadStatus().name();

            switch (state) {

                case "WON":
                    row.setWon(row.getWon() + 1);
                    break;

                case "LOST":
                    row.setLost(row.getLost() + 1);
                    break;

                case "DROPPED":
                    row.setDropped(row.getDropped() + 1);
                    break;

                case "UNRESPONSIVE":
                    row.setUnresponsive(row.getUnresponsive() + 1);
                    break;

                case "POSTPONED":
                    row.setPostponed(row.getPostponed() + 1);
                    break;

                default:
                    // "In Progress" only applies once a lead has become an
                    // Opportunity and its stage isn't one of the outcomes
                    // above - matches the live In Progress list's definition.
                    if (opportunity != null) {
                        row.setInProgress(row.getInProgress() + 1);
                    }
                    break;
            }
        }

        for (LeadReportResponse row : monthlyReport.values()) {

            if (row.getValidLeads() > 0) {

                row.setWonConversionRate(
                        (row.getWon() * 100.0)
                                / row.getValidLeads()
                );
            }
        }

        return new ArrayList<>(monthlyReport.values());
    }
}