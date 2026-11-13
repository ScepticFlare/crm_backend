package com.compact.crm.service;

import com.compact.crm.dto.response.DashboardStatsResponse;
import com.compact.crm.enums.OpportunitySubStatus;
import com.compact.crm.enums.SalesStageType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Dashboard pipeline has exactly the five main stages (IN PROGRESS, WON,
 * LOST, POSTPONED, DROPPED). It counts each Opportunity exactly once, treats
 * QUOTATION SENT / NEGOTIATION as part of IN PROGRESS (whether stored as a
 * sub-status or as the historical stage), counts the retired NEW stage as IN
 * PROGRESS, and decides the bucket from the stage's stable TYPE - so renaming
 * a stage cannot move anything. Opportunities still in a stage that is not a
 * main stage (UNRESPONSIVE, INVALID, custom) are counted separately in
 * otherStages - never dropped, never shown as extra main stages.
 */
class DashboardPipelineTallyTest {

    // [stage name, stage type, sub-status, count] - the shape of the
    // repository's grouped query.
    private static Object[] row(String name, SalesStageType type, OpportunitySubStatus sub, long count) {
        return new Object[]{name, type, sub, count};
    }

    private static long sum(Map<String, Long> m) {
        return m.values().stream().mapToLong(Long::longValue).sum();
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
                row("DROPPED", SalesStageType.LEGACY_DROPPED, null, 2),
                // not main stages
                row("UNRESPONSIVE", SalesStageType.LEGACY_UNRESPONSIVE, null, 1),
                row("INVALID", SalesStageType.LEGACY_INVALID, null, 1),
                row("Some Custom Stage", null, null, 3)
        ));

        Map<String, Long> pipeline = counts.pipeline();

        assertThat(pipeline).doesNotContainKey("NEW");
        assertThat(pipeline.get("IN_PROGRESS")).isEqualTo(4 + 3 + 5 + 2 + 7 + 6);
        assertThat(pipeline.get("POSTPONED")).isEqualTo(1);
        assertThat(pipeline.get("WON")).isEqualTo(9);
        assertThat(pipeline.get("LOST")).isEqualTo(8);
        assertThat(pipeline.get("DROPPED")).isEqualTo(2);

        Map<String, Long> other = counts.otherStages();

        assertThat(other.get("UNRESPONSIVE")).isEqualTo(1);
        assertThat(other.get("INVALID")).isEqualTo(1);
        assertThat(other.get("CUSTOM")).isEqualTo(3);

        long inputTotal = 4 + 3 + 5 + 2 + 7 + 6 + 1 + 9 + 8 + 2 + 1 + 1 + 3;
        assertThat(sum(pipeline) + sum(other)).isEqualTo(inputTotal);

        Map<String, Long> breakdown = counts.inProgressBreakdown();

        assertThat(breakdown.get("QUOTATION_SENT")).isEqualTo(5 + 7);
        assertThat(breakdown.get("NEGOTIATION")).isEqualTo(2 + 6);
        assertThat(breakdown.get("NO_SUB_STATUS")).isEqualTo(4 + 3);
        assertThat(sum(breakdown)).isEqualTo(pipeline.get("IN_PROGRESS"));
    }

    @Test
    void renamedMainStages_stillLandInTheirOwnBuckets() {

        DashboardStatsResponse.PipelineCounts counts = DashboardService.tally(List.of(
                row("Closed Won", SalesStageType.WON, null, 2),
                row("Dead", SalesStageType.LOST, null, 3),
                row("On Hold", SalesStageType.POSTPONED, null, 1),
                row("Working", SalesStageType.IN_PROGRESS, OpportunitySubStatus.NEGOTIATION, 4),
                row("Dropped by Customer", SalesStageType.LEGACY_DROPPED, null, 6)
        ));

        assertThat(counts.pipeline().get("WON")).isEqualTo(2);
        assertThat(counts.pipeline().get("LOST")).isEqualTo(3);
        assertThat(counts.pipeline().get("POSTPONED")).isEqualTo(1);
        assertThat(counts.pipeline().get("IN_PROGRESS")).isEqualTo(4);
        assertThat(counts.pipeline().get("DROPPED")).isEqualTo(6);
        assertThat(sum(counts.otherStages())).isZero();
        assertThat(counts.inProgressBreakdown().get("NEGOTIATION")).isEqualTo(4);
    }

    @Test
    void aStageNamedLikeAMainStageButWithoutAType_isNotCountedAsThatStage() {

        DashboardStatsResponse.PipelineCounts counts = DashboardService.tally(List.<Object[]>of(
                row("WON", null, null, 2)
        ));

        assertThat(counts.pipeline().get("WON")).isZero();
        assertThat(counts.otherStages().get("CUSTOM")).isEqualTo(2);
    }

    @Test
    void emptyPipeline_hasExactlyTheFiveMainStagesAtZero() {

        DashboardStatsResponse.PipelineCounts counts = DashboardService.tally(List.<Object[]>of());

        assertThat(counts.pipeline().keySet())
                .containsExactly("IN_PROGRESS", "POSTPONED", "WON", "LOST", "DROPPED");
        assertThat(counts.pipeline().values()).allMatch(v -> v == 0L);
        assertThat(counts.otherStages().keySet()).containsExactly("UNRESPONSIVE", "INVALID", "CUSTOM");
        assertThat(counts.otherStages().values()).allMatch(v -> v == 0L);
    }

    @Test
    void unresponsiveAndInvalid_areNotMainStages_whateverTheyAreCalledNow() {

        DashboardStatsResponse.PipelineCounts counts = DashboardService.tally(List.of(
                row("Gone Quiet", SalesStageType.LEGACY_UNRESPONSIVE, null, 1),
                row("Not a Fit", SalesStageType.LEGACY_INVALID, null, 4)
        ));

        assertThat(sum(counts.pipeline())).isZero();
        assertThat(counts.otherStages().get("UNRESPONSIVE")).isEqualTo(1);
        assertThat(counts.otherStages().get("INVALID")).isEqualTo(4);
    }
}
