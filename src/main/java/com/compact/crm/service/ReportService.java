package com.compact.crm.service;

import com.compact.crm.dto.response.LeadReportResponse;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Lead;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.Role;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ReportService {

    private final LeadRepository leadRepository;
    private final CurrentUserService currentUserService;

    public LeadReportResponse getLeadReport(LocalDate from, LocalDate to) {

        Employee currentEmployee = currentUserService.getCurrentEmployee();

        LocalDateTime fromDateTime = from.atStartOfDay();
        LocalDateTime toDateTime = to.atTime(23, 59, 59);

        List<Lead> leads;

        if (currentEmployee.getRole() == Role.ADMIN) {

            leads = leadRepository.findByCreatedAtBetween(
                    fromDateTime,
                    toDateTime
            );

        } else {

            leads = leadRepository.findByAssignedEmployeeAndCreatedAtBetween(
                    currentEmployee,
                    fromDateTime,
                    toDateTime
            );

        }

        long won = 0;
        long lost = 0;

        Map<String, Integer> employeeMap = new HashMap<>();
        Map<String, Integer> sourceMap = new HashMap<>();

        for (Lead lead : leads) {

            if (lead.getLeadStatus() == LeadStatus.WON) {
                won++;
            }

            if (lead.getLeadStatus() == LeadStatus.LOST) {
                lost++;
            }

            if (lead.getAssignedEmployee() != null) {

                String employeeName =
                        lead.getAssignedEmployee().getName();

                employeeMap.put(
                        employeeName,
                        employeeMap.getOrDefault(employeeName, 0) + 1
                );

            }

            if (lead.getLeadSource() != null) {

                String source =
                        lead.getLeadSource().getName();

                sourceMap.put(
                        source,
                        sourceMap.getOrDefault(source, 0) + 1
                );

            }

        }

        return LeadReportResponse.builder()
                .totalLeads(leads.size())
                .wonLeads(won)
                .lostLeads(lost)
                .leadsByEmployee(employeeMap)
                .leadsBySource(sourceMap)
                .build();

    }

}