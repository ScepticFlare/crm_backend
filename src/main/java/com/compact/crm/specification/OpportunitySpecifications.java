package com.compact.crm.specification;

import com.compact.crm.entity.LeadProduct;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.enums.LeadValidity;
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
}
