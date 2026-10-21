package com.compact.crm.service;

import com.compact.crm.dto.response.DashboardStatsResponse;
import com.compact.crm.enums.OpportunitySubStatus;
import com.compact.crm.enums.SalesStageType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Dashboard pipeline counts each Opportunity exactly once, treats
 * QUOTATION SENT / NEGOTIATION as part of IN PROGRESS (whether stored as a
 * sub-status or as the historical stage), and decides the bucket from the
 * stage's stable TYPE - so renaming a core stage cannot move anything.
 */
class DashboardPipelineTallyTest {

    // [stage name, stage type, sub-status, count] - the shape of the
    // repository's grouped query.
    private static Object[] row(String name, SalesStageType type, OpportunitySubStatus sub, long count) {
        return new Object[]{name, type, sub, count};
    }

    @Test
    void everyOpportunityIsCountedExactlyOnce_andInProgressBreakdownSumsToInProgress() {

        DashboardStatsResponse.PipelineCounts counts = DashboardService.tally(List.of(
                row("NEW", SalesStageType.NEW, null, 4),
                row("IN PROGRESS", SalesStageType.IN_PROGRESS, null, 3),
                row("IN PROGRESS", SalesStageType.IN_PROGRESS, OpportunitySubStatus.QUOTATION_SENT, 5),
                row("IN PROGRESS", SalesStageType.IN_PROGRESS, OpportunitySubStatus.NEGOTIATION, 2),
                // historical stages, still in the database
                row("QUOTATION SENT", SalesStageType.LEGACY_QUOTATION_SENT, null, 7),
                row("NEGOTIATION", SalesStageType.LEGACY_NEGOTIATION, null, 6),
                row("POSTPONED", SalesStageType.POSTPONED, null, 1),
                row("WON", SalesStageType.WON, null, 9),
                row("LOST", SalesStageType.LOST, null, 8),
                // not part of the five core buckets (untyped / custom)
                row("DROPPED", null, null, 2),
                row("UNRESPONSIVE", null, null, 1),
                row("INVALID", null, null, 1),
                row("Some Custom Stage", null, null, 3)
        ));

        Map<String, Long> pipeline = counts.pipeline();

        assertThat(pipeline.get("NEW")).isEqualTo(4);
        assertThat(pipeline.get("IN_PROGRESS")).isEqualTo(3 + 5 + 2 + 7 + 6);
        assertThat(pipeline.get("POSTPONED")).isEqualTo(1);
        assertThat(pipeline.get("WON")).isEqualTo(9);
        assertThat(pipeline.get("LOST")).isEqualTo(8);
        assertThat(pipeline.get("OTHER")).isEqualTo(2 + 1 + 1 + 3);

        long inputTotal = 4 + 3 + 5 + 2 + 7 + 6 + 1 + 9 + 8 + 2 + 1 + 1 + 3;
        assertThat(pipeline.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(inputTotal);

        Map<String, Long> breakdown = counts.inProgressBreakdown();

        assertThat(breakdown.get("QUOTATION_SENT")).isEqualTo(5 + 7);
        assertThat(breakdown.get("NEGOTIATION")).isEqualTo(2 + 6);
        assertThat(breakdown.get("NO_SUB_STATUS")).isEqualTo(3);
        assertThat(breakdown.values().stream().mapToLong(Long::longValue).sum())
                .isEqualTo(pipeline.get("IN_PROGRESS"));
    }

    @Test
    void renamedCoreStages_stillLandInTheirOwnBuckets() {

        DashboardStatsResponse.PipelineCounts counts = DashboardService.tally(List.of(
                row("Closed Won", SalesStageType.WON, null, 2),
                row("Dead", SalesStageType.LOST, null, 3),
                row("On Hold", SalesStageType.POSTPONED, null, 1),
                row("Working", SalesStageType.IN_PROGRESS, OpportunitySubStatus.NEGOTIATION, 4),
                row("Brand New", SalesStageType.NEW, null, 5)
        ));

        assertThat(counts.pipeline().get("WON")).isEqualTo(2);
        assertThat(counts.pipeline().get("LOST")).isEqualTo(3);
        assertThat(counts.pipeline().get("POSTPONED")).isEqualTo(1);
        assertThat(counts.pipeline().get("IN_PROGRESS")).isEqualTo(4);
        assertThat(counts.pipeline().get("NEW")).isEqualTo(5);
        assertThat(counts.pipeline().get("OTHER")).isZero();
        assertThat(counts.inProgressBreakdown().get("NEGOTIATION")).isEqualTo(4);
    }

    @Test
    void aStageNamedLikeACoreStageButWithoutAType_isNotCountedAsThatStage() {

        DashboardStatsResponse.PipelineCounts counts = DashboardService.tally(List.<Object[]>of(
                row("WON", null, null, 2)
        ));

        assertThat(counts.pipeline().get("WON")).isZero();
        assertThat(counts.pipeline().get("OTHER")).isEqualTo(2);
    }

    @Test
    void emptyPipeline_hasAllBucketsAtZero() {

        DashboardStatsResponse.PipelineCounts counts = DashboardService.tally(List.<Object[]>of());

        assertThat(counts.pipeline().keySet())
                .containsExactly("NEW", "IN_PROGRESS", "POSTPONED", "WON", "LOST", "OTHER");
        assertThat(counts.pipeline().values()).allMatch(v -> v == 0L);
    }

    @Test
    void typedLegacyClosedStages_areOther_whateverTheyAreCalledNow() {

        DashboardStatsResponse.PipelineCounts counts = DashboardService.tally(List.of(
                row("Dropped by Customer", SalesStageType.LEGACY_DROPPED, null, 2),
                row("Gone Quiet", SalesStageType.LEGACY_UNRESPONSIVE, null, 1),
                row("Not a Fit", SalesStageType.LEGACY_INVALID, null, 4)
        ));

        assertThat(counts.pipeline().get("OTHER")).isEqualTo(7);
        assertThat(counts.pipeline().get("IN_PROGRESS")).isZero();
    }
}
