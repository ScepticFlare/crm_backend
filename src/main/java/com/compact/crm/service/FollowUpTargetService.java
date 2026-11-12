package com.compact.crm.service;

import com.compact.crm.dto.response.FollowUpTargetResponse;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.enums.LeadCategory;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.OpportunityRepository;
import com.compact.crm.security.AccessControlService;
import com.compact.crm.security.CurrentUserService;
import com.compact.crm.specification.LeadSpecifications;
import com.compact.crm.specification.OpportunitySpecifications;
import com.compact.crm.specification.SpecificationUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static com.compact.crm.security.AccessControlService.FOLLOWUP_MANAGE;

// The records a follow-up can be scheduled for, for the "Related record"
// picker. Scoped by FOLLOWUP_MANAGE - the same permission and owner rule
// FollowUpService enforces when the follow-up is saved - so the picker never
// offers something the save would refuse (and the save still re-checks).
//
// Eligible:
//  - LEAD: unconverted leads that are still prospects (NEW, VALID_UNCONVERTED -
//    the operational Leads list). A CONVERTED lead is offered as its
//    Opportunity (with the lead's company shown): after conversion the
//    opportunity is the record work continues on, and its follow-ups already
//    count the lead's (FollowUpService). INVALID leads are not prospects and
//    are not offered here (Lead Details can still add one directly).
//  - OPPORTUNITY: every stage. Active / postponed opportunities need next
//    actions; won, lost and dropped ones may still legitimately be followed up
//    (after-sales, re-engagement). The stage is shown in the label.
@Service
@RequiredArgsConstructor
public class FollowUpTargetService {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 100;

    private final LeadRepository leadRepository;
    private final OpportunityRepository opportunityRepository;
    private final CurrentUserService currentUserService;
    private final AccessControlService accessControlService;

    @Transactional(readOnly = true)
    public List<FollowUpTargetResponse> search(String search, String type, Integer limit) {

        Employee actor = currentUserService.getCurrentEmployee();

        // throws (403) when the role cannot manage follow-ups at all
        List<Long> owners = accessControlService.resolveVisibleEmployeeIds(actor, FOLLOWUP_MANAGE);

        int size = limit == null || limit < 1 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        String q = search == null || search.isBlank() ? null : search.trim();
        String t = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);

        List<FollowUpTargetResponse> out = new ArrayList<>();

        if (t.isEmpty() || t.equals("LEAD")) {
            Specification<Lead> spec = SpecificationUtil.and(Arrays.asList(
                    LeadSpecifications.ownerIn(owners),
                    LeadSpecifications.inCategories(LeadCategory.ACTIVE),
                    LeadSpecifications.search(q)));
            leadRepository.findAll(spec, PageRequest.of(0, size, Sort.by("companyName", "id")))
                    .forEach(l -> out.add(FollowUpTargetResponse.lead(l)));
        }

        if (t.isEmpty() || t.equals("OPPORTUNITY")) {
            Specification<Opportunity> spec = SpecificationUtil.and(Arrays.asList(
                    OpportunitySpecifications.ownerIn(owners),
                    OpportunitySpecifications.search(q)));
            opportunityRepository.findAll(spec, PageRequest.of(0, size, Sort.by("lead.companyName", "title", "id")))
                    .forEach(o -> out.add(FollowUpTargetResponse.opportunity(o)));
        }

        return out;
    }
}
