package com.compact.crm.specification;

import com.compact.crm.entity.FollowUp;
import com.compact.crm.entity.LeadProduct;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.enums.FollowUpStatus;
import com.compact.crm.enums.LeadValidity;
import com.compact.crm.enums.SalesStageType;
import com.compact.crm.service.OpportunityWorkflow;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Fetch;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class OpportunitySpecifications {

    private OpportunitySpecifications() {
    }

    public static Specification<Opportunity> search(String search) {

        if (search == null || search.isBlank()) {
            return null;
        }

        String like = "%" + search.toLowerCase() + "%";

        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("title")), like),
                cb.like(cb.lower(root.get("lead").get("companyName")), like),
                cb.like(cb.lower(root.get("lead").get("contactPerson")), like),
                cb.like(cb.lower(root.get("lead").get("phone")), like),
                cb.like(cb.lower(root.get("lead").get("email")), like),
                cb.like(cb.lower(root.get("salesStage").get("name")), like)
        );
    }

    public static Specification<Opportunity> hasStageName(String stageName) {

        if (stageName == null || stageName.isBlank()) {
            return null;
        }

        return (root, query, cb) -> cb.equal(root.get("salesStage").get("name"), stageName);
    }

    public static Specification<Opportunity> excludingStageNames(List<String> stageNames) {

        if (stageNames == null || stageNames.isEmpty()) {
            return null;
        }

        return (root, query, cb) -> cb.not(root.get("salesStage").get("name").in(stageNames));
    }

    public static Specification<Opportunity> hasSalesStageId(Long salesStageId) {

        if (salesStageId == null) {
            return null;
        }

        return (root, query, cb) -> cb.equal(root.get("salesStage").get("id"), salesStageId);
    }

    public static Specification<Opportunity> hasAssignedEmployeeId(Long employeeId) {

        if (employeeId == null) {
            return null;
        }

        return (root, query, cb) -> cb.equal(root.get("lead").get("assignedEmployee").get("id"), employeeId);
    }

    public static Specification<Opportunity> hasIndustryId(Long industryId) {

        if (industryId == null) {
            return null;
        }

        return (root, query, cb) -> cb.equal(root.get("lead").get("industry").get("id"), industryId);
    }

    public static Specification<Opportunity> hasLeadValidity(LeadValidity leadValidity) {

        if (leadValidity == null) {
            return null;
        }

        return (root, query, cb) -> cb.equal(root.get("leadValidity"), leadValidity);
    }

    public static Specification<Opportunity> hasProductId(Long productId) {

        if (productId == null) {
            return null;
        }

        return (root, query, cb) -> {

            Subquery<Long> subquery = query.subquery(Long.class);
            var lp = subquery.from(LeadProduct.class);

            subquery.select(lp.get("id"));
            subquery.where(
                    cb.equal(lp.get("lead"), root.get("lead")),
                    cb.equal(lp.get("product").get("id"), productId)
            );

            return cb.exists(subquery);
        };
    }

    public static Specification<Opportunity> createdBetween(LocalDateTime from, LocalDateTime to) {

        if (from == null && to == null) {
            return null;
        }

        return (root, query, cb) -> {

            if (from != null && to != null) {
                return cb.between(root.get("createdAt"), from, to);
            }

            if (from != null) {
                return cb.greaterThanOrEqualTo(root.get("createdAt"), from);
            }

            return cb.lessThan(root.get("createdAt"), to);
        };
    }

    public static Specification<Opportunity> expectedClosingBetween(LocalDate from, LocalDate to) {

        if (from == null && to == null) {
            return null;
        }

        return (root, query, cb) -> {

            if (from != null && to != null) {
                return cb.between(root.get("expectedClosingDate"), from, to);
            }

            if (from != null) {
                return cb.greaterThanOrEqualTo(root.get("expectedClosingDate"), from);
            }

            return cb.lessThanOrEqualTo(root.get("expectedClosingDate"), to);
        };
    }

    public static Specification<Opportunity> valueBetween(Double min, Double max) {

        if (min == null && max == null) {
            return null;
        }

        return (root, query, cb) -> {

            if (min != null && max != null) {
                return cb.between(root.get("productValue"), min, max);
            }

            if (min != null) {
                return cb.greaterThanOrEqualTo(root.get("productValue"), min);
            }

            return cb.lessThanOrEqualTo(root.get("productValue"), max);
        };
    }


    // ---- Workflow warnings (closing date / next action / postponed review) ----

    // Stage types that mean "no longer being worked" (WON, LOST, POSTPONED).
    private static final List<SalesStageType> NON_ACTIVE_TYPES = java.util.Arrays.stream(SalesStageType.values())
            .filter(t -> !t.isActive())
            .toList();

    // Still being worked: a typed stage whose type is active, or a custom
    // (untyped) stage. Mirrors OpportunityWorkflow.isActive and - being type
    // based - is unaffected by an Admin renaming a stage or by a custom
    // stage being given a reserved-looking name.
    private static jakarta.persistence.criteria.Predicate activePredicate(
            jakarta.persistence.criteria.Root<Opportunity> root,
            jakarta.persistence.criteria.CriteriaBuilder cb) {

        jakarta.persistence.criteria.Path<Object> type = root.get("salesStage").get("stageType");

        return cb.or(
                cb.isNull(type),
                cb.not(type.in(NON_ACTIVE_TYPES))
        );
    }

    public static Specification<Opportunity> activeStage() {
        return (root, query, cb) -> activePredicate(root, cb);
    }

    // Filter by stable stage type (e.g. the Postponed / Lost pages), so a
    // renamed core stage still shows up where it should.
    public static Specification<Opportunity> hasStageType(SalesStageType stageType) {

        if (stageType == null) {
            return null;
        }

        return (root, query, cb) -> cb.equal(root.get("salesStage").get("stageType"), stageType);
    }

    // Active Opportunity whose expected closing date is before `today`.
    public static Specification<Opportunity> closingDateExpired(LocalDate today) {

        return (root, query, cb) -> cb.and(
                cb.isNotNull(root.get("expectedClosingDate")),
                cb.lessThan(root.<LocalDate>get("expectedClosingDate"), today),
                activePredicate(root, cb)
        );
    }

    // No QUALIFYING follow-up on the Opportunity itself or on its originating
    // Lead: PENDING, scheduled `now` or later, with a described next action
    // (remarks of at least FollowUpRules.MIN_NEXT_ACTION_LENGTH characters) -
    // the same definition as service.FollowUpRules.
    public static Specification<Opportunity> noQualifyingFollowUp(LocalDateTime now) {

        return (root, query, cb) -> {

            Subquery<Long> sub = query.subquery(Long.class);
            Root<FollowUp> followUp = sub.from(FollowUp.class);

            sub.select(followUp.<Long>get("id")).where(
                    cb.equal(followUp.get("status"), FollowUpStatus.PENDING),
                    cb.greaterThanOrEqualTo(followUp.<LocalDateTime>get("scheduledDate"), now),
                    cb.isNotNull(followUp.get("remarks")),
                    cb.greaterThanOrEqualTo(
                            cb.length(cb.trim(followUp.<String>get("remarks"))),
                            com.compact.crm.service.FollowUpRules.MIN_NEXT_ACTION_LENGTH),
                    cb.or(
                            cb.equal(followUp.get("opportunity").get("id"), root.get("id")),
                            cb.equal(followUp.get("lead").get("id"), root.get("lead").get("id"))
                    )
            );

            return cb.not(cb.exists(sub));
        };
    }

    // POSTPONED, review date reached, and not yet acknowledged for that date.
    public static Specification<Opportunity> postponedReminderDue(LocalDate today) {

        return (root, query, cb) -> cb.and(
                cb.equal(root.get("salesStage").get("stageType"), SalesStageType.POSTPONED),
                cb.isNotNull(root.get("postponedReviewDate")),
                cb.lessThanOrEqualTo(root.<LocalDate>get("postponedReviewDate"), today),
                cb.or(
                        cb.isNull(root.get("postponedReminderAcknowledgedFor")),
                        cb.notEqual(root.get("postponedReminderAcknowledgedFor"), root.get("postponedReviewDate"))
                )
        );
    }

    // RBAC scope - Opportunity has no owner field of its own, ownership is
    // via lead.assignedEmployee (same source getAuthorizedOpportunity uses).
    public static Specification<Opportunity> ownerIn(List<Long> employeeIds) {

        if (employeeIds == null) {
            return null;
        }

        return (root, query, cb) -> root.get("lead").get("assignedEmployee").get("id").in(employeeIds);
    }

    public static Specification<Opportunity> hasIds(List<Long> ids) {

        if (ids == null) {
            return null;
        }

        return (root, query, cb) -> root.get("id").in(ids);
    }

    // Opportunity is returned to the frontend whole (controller.
    // OpportunityController), and its lead - EAGER by default, same as
    // salesStage - is serialized in full too (pages/Opportunities.jsx reads
    // lead.companyName / lead.assignedEmployee.name). Left un-fetched, a
    // Criteria/Specification list query hits the DB separately per row for
    // salesStage, lead, lead.industry, lead.leadSource AND
    // lead.assignedEmployee - up to 250 extra queries for a 50-row page.
    // All single-valued (no cartesian row multiplication), so one LEFT JOIN
    // chain is safe with pagination. Skipped on the count query, same as
    // LeadSpecifications.fetchAssociations.
    public static Specification<Opportunity> fetchAssociations() {

        return (root, query, cb) -> {

            if (query.getResultType() != Long.class && query.getResultType() != long.class) {
                root.fetch("salesStage", JoinType.LEFT);
                Fetch<Opportunity, ?> leadFetch = root.fetch("lead", JoinType.LEFT);
                leadFetch.fetch("industry", JoinType.LEFT);
                leadFetch.fetch("leadSource", JoinType.LEFT);
                leadFetch.fetch("assignedEmployee", JoinType.LEFT);
            }

            return cb.conjunction();
        };
    }
}
