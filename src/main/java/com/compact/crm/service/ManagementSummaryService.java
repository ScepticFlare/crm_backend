package com.compact.crm.service;

import com.compact.crm.dto.response.ManagementSummaryResponse;
import com.compact.crm.dto.response.ManagementSummaryResponse.*;
import com.compact.crm.entity.Employee;
import com.compact.crm.enums.SalesStageType;
import com.compact.crm.security.AccessControlService;
import com.compact.crm.security.CurrentUserService;
import com.compact.crm.util.BusinessClock;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.compact.crm.security.AccessControlService.FOLLOWUP_VIEW;
import static com.compact.crm.security.AccessControlService.LEAD_VIEW;
import static com.compact.crm.security.AccessControlService.OPPORTUNITY_VIEW;

// The Director's management metrics. Read-only; every query is restricted to
// the same visibility scope the corresponding list endpoints use (Admin all,
// Manager team, Employee own). Figures that cannot be exact with the data the
// CRM stores say so in `notes` rather than pretending.
@Service
@RequiredArgsConstructor
public class ManagementSummaryService {

    private static final int TOP_N = 10;

    @PersistenceContext
    private EntityManager em;

    private final CurrentUserService currentUserService;
    private final AccessControlService accessControlService;

    // Stage types that mean "no longer being worked" (WON, LOST, POSTPONED,
    // and the historical closed stages) - the single definition of "active".
    private static final List<SalesStageType> NON_ACTIVE = java.util.Arrays.stream(SalesStageType.values())
            .filter(t -> !t.isActive()).toList();

    private static final String ACTIVE =
            " (s.stageType IS NULL OR s.stageType NOT IN :nonActive) ";

    @Transactional(readOnly = true)
    public ManagementSummaryResponse getSummary(LocalDate from, LocalDate to) {

        LocalDate today = BusinessClock.today();

        LocalDate end = to != null ? to : today;
        LocalDate start = from != null ? from : end.minusDays(29);

        Employee me = currentUserService.getCurrentEmployee();

        List<Long> leadIds = accessControlService.resolveVisibleEmployeeIds(me, LEAD_VIEW);
        List<Long> oppIds = accessControlService.resolveVisibleEmployeeIds(me, OPPORTUNITY_VIEW);
        List<Long> followUpIds = accessControlService.resolveVisibleEmployeeIds(me, FOLLOWUP_VIEW);

        LocalDateTime rangeStart = start.atStartOfDay();
        LocalDateTime rangeEnd = end.plusDays(1).atStartOfDay();

        List<String> notes = List.of(
                "Funnel: leads created in the selected period, shown as NEW / VALID / INVALID (an older "
                        + "legacy status is mapped as described on the Leads page; a lead that already has an "
                        + "opportunity counts as VALID). 'Currently at Quotation Sent' is the opportunity's present "
                        + "state, not 'ever quoted'.",
                "Wins/losses by salesperson and source count opportunities by their CURRENT stage over all time - "
                        + "the CRM does not store a closed date for older records.",
                "Closure-date changes come from the field-level history, which only exists for changes made "
                        + "since that history was introduced.",
                "'Open pipeline' and the closing windows cover active opportunities (not WON, LOST, POSTPONED or "
                        + "a historical closed stage); the value is the opportunity's Product Value.");

        if (isEmpty(leadIds) && isEmpty(oppIds) && isEmpty(followUpIds)) {
            return empty(start, end, notes);
        }

        return new ManagementSummaryResponse(
                start, end,
                funnel(leadIds, oppIds, rangeStart, rangeEnd),
                pipeline(oppIds),
                overdue(oppIds, followUpIds, today),
                closingSoon(oppIds, today),
                resultsBySalesperson(oppIds),
                resultsBySource(oppIds),
                lossReasons(oppIds),
                closureDateChanges(oppIds),
                uncontacted(leadIds, today),
                notes
        );
    }

    private static boolean isEmpty(List<Long> ids) {
        return ids != null && ids.isEmpty();
    }

    private ManagementSummaryResponse empty(LocalDate from, LocalDate to, List<String> notes) {

        return new ManagementSummaryResponse(
                from, to,
                new Funnel(0, 0, 0, 0, 0, 0, 0, 0, 0),
                new Pipeline(0, 0, List.of()),
                new Overdue(0, 0, List.of(), 0, List.of()),
                List.of(new ClosingWindow(7, 0, 0), new ClosingWindow(15, 0, 0), new ClosingWindow(30, 0, 0)),
                List.of(), List.of(),
                new LossReasons(List.of(), 0),
                new ClosureDateChanges(0, List.of()),
                new UncontactedLeads(0, List.of()),
                notes);
    }

    // ------------------------------------------------------------ helpers

    // " AND <alias>.assignedEmployee.id IN :ids" when the scope is restricted.
    private static String scope(List<Long> ids, String ownerPath) {
        return ids == null ? "" : " AND " + ownerPath + " IN :ids ";
    }

    private Query query(String jpql, List<Long> ids) {

        Query q = em.createQuery(jpql);

        if (ids != null && jpql.contains(":ids")) {
            q.setParameter("ids", ids);
        }

        if (jpql.contains(":nonActive")) {
            q.setParameter("nonActive", NON_ACTIVE);
        }

        return q;
    }

    private static long asLong(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    private static double asDouble(Object o) {
        return o == null ? 0 : ((Number) o).doubleValue();
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    // ------------------------------------------------------------ funnel

    private Funnel funnel(List<Long> leadIds, List<Long> oppIds, LocalDateTime from, LocalDateTime to) {

        String inRange = " WHERE l.createdAt >= :from AND l.createdAt < :to " + scope(leadIds, "l.assignedEmployee.id");
        String hasOpp = " EXISTS (SELECT 1 FROM Opportunity x WHERE x.lead = l) ";
        String invalid = "l.leadStatus = com.compact.crm.enums.LeadStatus.INVALID";
        String valid = "l.leadStatus = com.compact.crm.enums.LeadStatus.VALID";

        long received = count("SELECT COUNT(l) FROM Lead l" + inRange, leadIds, from, to);
        long invalidCount = count("SELECT COUNT(l) FROM Lead l" + inRange + " AND " + invalid, leadIds, from, to);
        long validCount = count("SELECT COUNT(l) FROM Lead l" + inRange + " AND NOT (" + invalid + ") AND ("
                + valid + " OR " + hasOpp + ")", leadIds, from, to);
        long converted = count("SELECT COUNT(l) FROM Lead l" + inRange + " AND " + hasOpp, leadIds, from, to);

        String oppRange = " JOIN o.lead l JOIN o.salesStage s WHERE l.createdAt >= :from AND l.createdAt < :to "
                + scope(oppIds, "l.assignedEmployee.id");

        long quotation = count("SELECT COUNT(o) FROM Opportunity o" + oppRange
                + " AND (o.subStatus = com.compact.crm.enums.OpportunitySubStatus.QUOTATION_SENT"
                + " OR s.stageType = com.compact.crm.enums.SalesStageType.LEGACY_QUOTATION_SENT)", oppIds, from, to);

        long won = count("SELECT COUNT(o) FROM Opportunity o" + oppRange
                + " AND s.stageType = com.compact.crm.enums.SalesStageType.WON", oppIds, from, to);

        // All NEW (not yet qualified, not converted) leads right now, whatever
        // their creation date.
        long newNow = asLong(query("SELECT COUNT(l) FROM Lead l WHERE NOT (" + invalid + ") AND NOT (" + valid
                + ") AND NOT (" + hasOpp + ")" + scope(leadIds, "l.assignedEmployee.id"), leadIds).getSingleResult());

        long newInRange = received - invalidCount - validCount;

        double rate = received == 0 ? 0 : round2(validCount * 100.0 / received);

        return new Funnel(received, newInRange, validCount, invalidCount, rate, converted, quotation, won, newNow);
    }

    private long count(String jpql, List<Long> ids, LocalDateTime from, LocalDateTime to) {

        Query q = query(jpql, ids);
        q.setParameter("from", from);
        q.setParameter("to", to);

        return asLong(q.getSingleResult());
    }

    // ------------------------------------------------------------ pipeline

    private Pipeline pipeline(List<Long> oppIds) {

        String base = " FROM Opportunity o JOIN o.salesStage s JOIN o.lead l WHERE " + ACTIVE
                + scope(oppIds, "l.assignedEmployee.id");

        Object[] totals = (Object[]) query("SELECT COUNT(o), COALESCE(SUM(o.productValue), 0)" + base, oppIds)
                .getSingleResult();

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query("SELECT s.name, COUNT(o), COALESCE(SUM(o.productValue), 0)" + base
                + " GROUP BY s.name ORDER BY COUNT(o) DESC", oppIds).getResultList();

        List<StageRow> byStage = rows.stream()
                .map(r -> new StageRow((String) r[0], asLong(r[1]), round2(asDouble(r[2]))))
                .toList();

        return new Pipeline(asLong(totals[0]), round2(asDouble(totals[1])), byStage);
    }

    // ------------------------------------------------------------ overdue

    private Overdue overdue(List<Long> oppIds, List<Long> followUpIds, LocalDate today) {

        String base = " FROM Opportunity o JOIN o.salesStage s JOIN o.lead l WHERE " + ACTIVE
                + " AND o.expectedClosingDate IS NOT NULL AND o.expectedClosingDate < :today "
                + scope(oppIds, "l.assignedEmployee.id");

        Query summary = query("SELECT COUNT(o), COALESCE(SUM(o.productValue), 0)" + base, oppIds);
        summary.setParameter("today", today);
        Object[] totals = (Object[]) summary.getSingleResult();

        Query list = query("SELECT o.id, o.title, l.companyName, l.assignedEmployee.name, o.expectedClosingDate, "
                + "COALESCE(o.productValue, 0)" + base + " ORDER BY o.expectedClosingDate ASC", oppIds);
        list.setParameter("today", today);
        list.setMaxResults(TOP_N);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = list.getResultList();

        List<OpportunityItem> items = rows.stream()
                .map(r -> new OpportunityItem((Long) r[0], (String) r[1], (String) r[2], (String) r[3],
                        (LocalDate) r[4], round2(asDouble(r[5]))))
                .toList();

        // Overdue follow-ups: PENDING and scheduled before now - on the lead
        // itself or on its opportunity.
        String fBase = " FROM FollowUp f LEFT JOIN f.lead fl LEFT JOIN f.opportunity fo LEFT JOIN fo.lead ol "
                + " WHERE f.status = com.compact.crm.enums.FollowUpStatus.PENDING AND f.scheduledDate < :now "
                + (followUpIds == null ? ""
                : " AND (fl.assignedEmployee.id IN :ids OR ol.assignedEmployee.id IN :ids) ");

        LocalDateTime now = BusinessClock.startOfToday();

        Query fCount = query("SELECT COUNT(f)" + fBase, followUpIds);
        fCount.setParameter("now", now);
        long followUpCount = asLong(fCount.getSingleResult());

        Query fList = query("SELECT f.id, f.scheduledDate, COALESCE(fl.companyName, ol.companyName), fo.id, fl.id"
                + fBase + " ORDER BY f.scheduledDate ASC", followUpIds);
        fList.setParameter("now", now);
        fList.setMaxResults(TOP_N);

        @SuppressWarnings("unchecked")
        List<Object[]> fRows = fList.getResultList();

        List<FollowUpItem> followUps = fRows.stream()
                .map(r -> new FollowUpItem((Long) r[0], (LocalDateTime) r[1], (String) r[2], (Long) r[3], (Long) r[4]))
                .toList();

        return new Overdue(asLong(totals[0]), round2(asDouble(totals[1])), items, followUpCount, followUps);
    }

    // ------------------------------------------------------------ closing soon

    private List<ClosingWindow> closingSoon(List<Long> oppIds, LocalDate today) {

        List<ClosingWindow> windows = new ArrayList<>();

        for (int days : new int[]{7, 15, 30}) {

            Query q = query("SELECT COUNT(o), COALESCE(SUM(o.productValue), 0) FROM Opportunity o "
                    + "JOIN o.salesStage s JOIN o.lead l WHERE " + ACTIVE
                    + " AND o.expectedClosingDate IS NOT NULL AND o.expectedClosingDate >= :today "
                    + " AND o.expectedClosingDate <= :until " + scope(oppIds, "l.assignedEmployee.id"), oppIds);

            q.setParameter("today", today);
            q.setParameter("until", today.plusDays(days));

            Object[] r = (Object[]) q.getSingleResult();

            windows.add(new ClosingWindow(days, asLong(r[0]), round2(asDouble(r[1]))));
        }

        return windows;
    }

    // ------------------------------------------------------------ wins / losses

    private static final String RESULT_COLUMNS =
            ", SUM(CASE WHEN s.stageType = com.compact.crm.enums.SalesStageType.WON THEN 1 ELSE 0 END)"
                    + ", SUM(CASE WHEN s.stageType = com.compact.crm.enums.SalesStageType.WON "
                    + "THEN COALESCE(o.productValue, 0) ELSE 0 END)"
                    + ", SUM(CASE WHEN s.stageType = com.compact.crm.enums.SalesStageType.LOST THEN 1 ELSE 0 END)";

    private List<OwnerResult> resultsBySalesperson(List<Long> oppIds) {

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query("SELECT e.name" + RESULT_COLUMNS
                + " FROM Opportunity o JOIN o.salesStage s JOIN o.lead l JOIN l.assignedEmployee e "
                + " WHERE s.stageType IN (com.compact.crm.enums.SalesStageType.WON, "
                + "com.compact.crm.enums.SalesStageType.LOST) " + scope(oppIds, "e.id")
                + " GROUP BY e.id, e.name ORDER BY 2 DESC", oppIds).getResultList();

        return rows.stream()
                .map(r -> new OwnerResult((String) r[0], asLong(r[1]), round2(asDouble(r[2])), asLong(r[3])))
                .toList();
    }

    private List<SourceResult> resultsBySource(List<Long> oppIds) {

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query("SELECT COALESCE(ls.name, '(no source)')" + RESULT_COLUMNS
                + " FROM Opportunity o JOIN o.salesStage s JOIN o.lead l LEFT JOIN l.leadSource ls "
                + " WHERE s.stageType IN (com.compact.crm.enums.SalesStageType.WON, "
                + "com.compact.crm.enums.SalesStageType.LOST) " + scope(oppIds, "l.assignedEmployee.id")
                + " GROUP BY COALESCE(ls.name, '(no source)') ORDER BY 2 DESC", oppIds).getResultList();

        return rows.stream()
                .map(r -> new SourceResult((String) r[0], asLong(r[1]), round2(asDouble(r[2])), asLong(r[3])))
                .toList();
    }

    // ------------------------------------------------------------ loss reasons

    // Grouped in Java (case/whitespace-insensitive) so it does not depend on
    // how the database handles functions over a TEXT column.
    private LossReasons lossReasons(List<Long> oppIds) {

        @SuppressWarnings("unchecked")
        List<String> reasons = query("SELECT o.lostReason FROM Opportunity o JOIN o.salesStage s JOIN o.lead l "
                + "WHERE s.stageType = com.compact.crm.enums.SalesStageType.LOST "
                + scope(oppIds, "l.assignedEmployee.id"), oppIds).getResultList();

        Map<String, long[]> counts = new LinkedHashMap<>();
        Map<String, String> display = new LinkedHashMap<>();
        long without = 0;

        for (String reason : reasons) {

            if (reason == null || reason.isBlank()) {
                without++;
                continue;
            }

            String key = reason.trim().toLowerCase();

            counts.computeIfAbsent(key, k -> new long[1])[0]++;
            display.putIfAbsent(key, reason.trim());
        }

        List<ReasonCount> top = counts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .limit(TOP_N)
                .map(e -> new ReasonCount(display.get(e.getKey()), e.getValue()[0]))
                .toList();

        return new LossReasons(top, without);
    }

    // ------------------------------------------------------------ repeated closing-date changes

    private ClosureDateChanges closureDateChanges(List<Long> oppIds) {

        String base = " FROM ActivityLog a WHERE a.module = com.compact.crm.enums.ActivityModule.OPPORTUNITY "
                + " AND a.fieldName = 'expectedClosingDate' AND a.oldValue IS NOT NULL "
                + (oppIds == null ? ""
                : " AND a.entityId IN (SELECT o.id FROM Opportunity o WHERE o.lead.assignedEmployee.id IN :ids) ")
                + " GROUP BY a.entityId HAVING COUNT(a) >= 2 ";

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query("SELECT a.entityId, MAX(a.entityName), COUNT(a)" + base
                + " ORDER BY COUNT(a) DESC", oppIds).getResultList();

        List<ChangeCount> top = rows.stream()
                .limit(TOP_N)
                .map(r -> new ChangeCount((Long) r[0], (String) r[1], asLong(r[2])))
                .toList();

        return new ClosureDateChanges(rows.size(), top);
    }

    // ------------------------------------------------------------ leads not contacted by deadline

    private UncontactedLeads uncontacted(List<Long> leadIds, LocalDate today) {

        String base = " FROM Lead l WHERE l.leadStatus NOT IN (com.compact.crm.enums.LeadStatus.VALID, "
                + "com.compact.crm.enums.LeadStatus.INVALID) "
                + " AND NOT EXISTS (SELECT 1 FROM Opportunity x WHERE x.lead = l) "
                + " AND l.contactDeadline IS NOT NULL AND l.contactDeadline < :today "
                + " AND NOT EXISTS (SELECT 1 FROM FollowUp f WHERE f.lead = l "
                + "AND f.status = com.compact.crm.enums.FollowUpStatus.COMPLETED) "
                + scope(leadIds, "l.assignedEmployee.id");

        Query c = query("SELECT COUNT(l)" + base, leadIds);
        c.setParameter("today", today);

        Query list = query("SELECT l.id, l.companyName, l.assignedEmployee.name, l.contactDeadline" + base
                + " ORDER BY l.contactDeadline ASC", leadIds);
        list.setParameter("today", today);
        list.setMaxResults(TOP_N);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = list.getResultList();

        return new UncontactedLeads(asLong(c.getSingleResult()), rows.stream()
                .map(r -> new LeadItem((Long) r[0], (String) r[1], (String) r[2], (LocalDate) r[3]))
                .toList());
    }
}
