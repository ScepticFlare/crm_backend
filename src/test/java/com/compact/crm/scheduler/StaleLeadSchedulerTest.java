package com.compact.crm.scheduler;

import com.compact.crm.entity.Lead;
import com.compact.crm.enums.ActivityAction;
import com.compact.crm.enums.ActivityModule;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.service.ActivityLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the scheduler's own loop logic (given whatever
 * LeadRepository.findStaleActiveLeads returns) - the query's actual
 * selection criteria (date/status/follow-up/conversion filtering) is
 * covered separately by StaleLeadRepositoryIntegrationTest against a real
 * H2 database.
 */
@ExtendWith(MockitoExtension.class)
class StaleLeadSchedulerTest {

    @Mock private LeadRepository leadRepository;
    @Mock private ActivityLogService activityLogService;

    private StaleLeadScheduler scheduler;

    @BeforeEach
    void setUp() throws Exception {

        scheduler = new StaleLeadScheduler(leadRepository, activityLogService);

        // staleAfterMonths is populated by @Value at runtime; set the
        // default explicitly here since this test constructs the bean by
        // hand.
        var field = StaleLeadScheduler.class.getDeclaredField("staleAfterMonths");
        field.setAccessible(true);
        field.set(scheduler, 6);
    }

    @Test
    void marksEachReturnedLeadInactive_andLogsTheAutomaticAction() {

        Lead staleLead = Lead.builder().id(500L).companyName("Stale Co").leadStatus(LeadStatus.NEW).build();

        when(leadRepository.findStaleActiveLeads(anyList(), any(LocalDateTime.class)))
                .thenReturn(List.of(staleLead));

        scheduler.deactivateStaleLeads();

        assertThat(staleLead.getLeadStatus()).isEqualTo(LeadStatus.INACTIVE);
        verify(leadRepository).save(staleLead);

        // actor is null (system action, not an authenticated employee) -
        // ActivityLogService.log already handles a null actor gracefully.
        verify(activityLogService).log(
                isNull(), eq(ActivityModule.LEAD), eq(ActivityAction.UPDATE),
                eq(500L), eq("Stale Co"), any());
    }

    @Test
    void noStaleLeads_touchesNothing() {

        when(leadRepository.findStaleActiveLeads(anyList(), any(LocalDateTime.class)))
                .thenReturn(List.of());

        scheduler.deactivateStaleLeads();

        verify(leadRepository, never()).save(any(Lead.class));
        verify(activityLogService, never()).log(any(), any(), any(), any(), any(), any());
    }

    @Test
    void alreadyInactiveLead_neverReturnedByRepository_isNotReprocessed() {

        // The idempotency guarantee lives in the repository query itself
        // (leadStatus IN activeStatuses excludes INACTIVE) - this test pins
        // that the scheduler's loop has no separate "already processed"
        // branch that could disagree with it: given an empty result (as
        // the real query would return once every stale lead has already
        // been flipped), a second run is a pure no-op.
        when(leadRepository.findStaleActiveLeads(anyList(), any(LocalDateTime.class)))
                .thenReturn(List.of());

        scheduler.deactivateStaleLeads();
        scheduler.deactivateStaleLeads();

        verify(leadRepository, times(2)).findStaleActiveLeads(anyList(), any(LocalDateTime.class));
        verify(leadRepository, never()).save(any(Lead.class));
    }
}
