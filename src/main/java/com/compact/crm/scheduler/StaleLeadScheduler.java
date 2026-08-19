package com.compact.crm.scheduler;

import com.compact.crm.entity.Lead;
import com.compact.crm.enums.ActivityAction;
import com.compact.crm.enums.ActivityModule;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.service.ActivityLogService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

// Automatic stale-lead -> Invalid transition (business rule: a Lead with no
// meaningful activity for staleAfterMonths+ becomes Invalid on its own,
// without anyone needing to open the Leads page). Reuses the existing
// LeadStatus.INVALID value - the same one the "Invalid Leads" page/route
// (LeadController.getInvalidLeads) already filters by - rather than
// introducing a second invalid concept alongside it.
//
// "Touched" = LeadRepository.findStaleActiveLeads: the Lead's own
// updatedAt (bumped by LeadService.createLead/updateLead - see Lead's
// @PrePersist/@PreUpdate) or any FollowUp linked to it being created/
// updated, both within the window. Deliberately excludes plain viewing
// (LeadService.getLeadById never saves the entity, so it never advances
// updatedAt) per the business rule that viewing alone isn't meaningful
// activity.
//
// Only "open" leads (NEW/CONTACTED/QUOTATION_SENT/NEGOTIATION) not yet
// converted to an Opportunity are eligible - resolved outcomes
// (WON/LOST/DROPPED/UNRESPONSIVE) and already-INVALID leads are left
// alone. Idempotency comes from the query itself: once a lead's status
// flips to INVALID it no longer matches `leadStatus IN activeStatuses` on
// the next run, so nothing needs a separate "already processed" flag, and
// no other Lead field is touched.
@Component
@RequiredArgsConstructor
public class StaleLeadScheduler {

    private static final Logger log = LoggerFactory.getLogger(StaleLeadScheduler.class);

    private static final List<LeadStatus> ACTIVE_STATUSES = List.of(
            LeadStatus.NEW, LeadStatus.CONTACTED, LeadStatus.QUOTATION_SENT, LeadStatus.NEGOTIATION
    );

    private final LeadRepository leadRepository;
    private final ActivityLogService activityLogService;

    // Single, named place the "6 months" business rule lives - see
    // application.properties (crm.lead.stale-after-months) rather than a
    // magic number scattered across the query/service.
    @Value("${crm.lead.stale-after-months:6}")
    private int staleAfterMonths;

    // Runs once a day at 02:00 server time by default - overridable via
    // crm.lead.stale-check-cron without a code change.
    @Scheduled(cron = "${crm.lead.stale-check-cron:0 0 2 * * *}")
    @Transactional
    public void invalidateStaleLeads() {

        LocalDateTime cutoff = LocalDateTime.now().minusMonths(staleAfterMonths);

        List<Lead> staleLeads = leadRepository.findStaleActiveLeads(ACTIVE_STATUSES, cutoff);

        if (staleLeads.isEmpty()) {
            return;
        }

        for (Lead lead : staleLeads) {

            lead.setLeadStatus(LeadStatus.INVALID);
            leadRepository.save(lead);

            // actor is null - this is a system action, not performed by any
            // authenticated employee. ActivityLogService.log already treats
            // a null actor as "Unknown" for the employeeName snapshot.
            activityLogService.log(
                    null, ActivityModule.LEAD, ActivityAction.UPDATE,
                    lead.getId(), lead.getCompanyName(),
                    "Automatically marked Invalid: no meaningful activity for " + staleAfterMonths + "+ months"
            );
        }

        log.info(
                "Stale-lead job: marked {} lead(s) Invalid (no activity for {}+ months)",
                staleLeads.size(), staleAfterMonths
        );
    }
}
