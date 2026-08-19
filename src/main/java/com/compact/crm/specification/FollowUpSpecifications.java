package com.compact.crm.specification;

import com.compact.crm.entity.FollowUp;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.enums.FollowUpStatus;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.List;

public final class FollowUpSpecifications {

    private FollowUpSpecifications() {
    }

    // Search + ownerIn both need to reach lead.assignedEmployee /
    // opportunity.lead.assignedEmployee via LEFT JOINs (a FollowUp belongs
    // to exactly one of Lead or Opportunity). Combining two Specifications
    // that each independently join the same association can otherwise
    // duplicate rows, so both mark the query distinct.
    public static Specification<FollowUp> search(String search) {

        if (search == null || search.isBlank()) {
            return null;
        }

        String like = "%" + search.toLowerCase() + "%";

        return (root, query, cb) -> {

            query.distinct(true);

            Join<FollowUp, Lead> leadJoin = root.join("lead", JoinType.LEFT);
            Join<FollowUp, Opportunity> oppJoin = root.join("opportunity", JoinType.LEFT);
            Join<Opportunity, Lead> oppLeadJoin = oppJoin.join("lead", JoinType.LEFT);

            return cb.or(
                    cb.like(cb.lower(cb.coalesce(root.get("remarks"), "")), like),
                    cb.like(cb.lower(root.get("activityType").get("name")), like),
                    cb.like(cb.lower(root.get("employee").get("name")), like),
                    cb.like(cb.lower(cb.coalesce(leadJoin.get("companyName"), "")), like),
                    cb.like(cb.lower(cb.coalesce(oppLeadJoin.get("companyName"), "")), like)
            );
        };
    }

    public static Specification<FollowUp> hasStatus(FollowUpStatus status) {

        if (status == null) {
            return null;
        }

        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<FollowUp> hasActivityTypeId(Long activityTypeId) {

        if (activityTypeId == null) {
            return null;
        }

        return (root, query, cb) -> cb.equal(root.get("activityType").get("id"), activityTypeId);
    }

    // The employee assigned to carry out the follow-up (FollowUp.employee)
    // - distinct from record ownership/RBAC scope (ownerIn below).
    public static Specification<FollowUp> hasEmployeeId(Long employeeId) {

        if (employeeId == null) {
            return null;
        }

        return (root, query, cb) -> cb.equal(root.get("employee").get("id"), employeeId);
    }

    public static Specification<FollowUp> hasLeadId(Long leadId) {

        if (leadId == null) {
            return null;
        }

        return (root, query, cb) -> cb.equal(root.get("lead").get("id"), leadId);
    }

    public static Specification<FollowUp> hasOpportunityId(Long opportunityId) {

        if (opportunityId == null) {
            return null;
        }

        return (root, query, cb) -> cb.equal(root.get("opportunity").get("id"), opportunityId);
    }

    public static Specification<FollowUp> scheduledBetween(LocalDateTime from, LocalDateTime to) {

        if (from == null && to == null) {
            return null;
        }

        return (root, query, cb) -> {

            if (from != null && to != null) {
                return cb.between(root.get("scheduledDate"), from, to);
            }

            if (from != null) {
                return cb.greaterThanOrEqualTo(root.get("scheduledDate"), from);
            }

            return cb.lessThan(root.get("scheduledDate"), to);
        };
    }

    // RBAC scope - a FollowUp's owner is whichever parent (Lead, or
    // Opportunity's Lead) is set, same resolution
    // FollowUpService.getAuthorizedFollowUp already uses.
    public static Specification<FollowUp> ownerIn(List<Long> employeeIds) {

        if (employeeIds == null) {
            return null;
        }

        return (root, query, cb) -> {

            query.distinct(true);

            Join<FollowUp, Lead> leadJoin = root.join("lead", JoinType.LEFT);
            Join<FollowUp, Opportunity> oppJoin = root.join("opportunity", JoinType.LEFT);
            Join<Opportunity, Lead> oppLeadJoin = oppJoin.join("lead", JoinType.LEFT);

            return cb.or(
                    leadJoin.get("assignedEmployee").get("id").in(employeeIds),
                    oppLeadJoin.get("assignedEmployee").get("id").in(employeeIds)
            );
        };
    }

    public static Specification<FollowUp> hasIds(List<Long> ids) {

        if (ids == null) {
            return null;
        }

        return (root, query, cb) -> root.get("id").in(ids);
    }
}
