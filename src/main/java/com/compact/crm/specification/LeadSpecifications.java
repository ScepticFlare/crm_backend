package com.compact.crm.specification;

import com.compact.crm.entity.Lead;
import com.compact.crm.entity.LeadBattery;
import com.compact.crm.entity.LeadProduct;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.LeadValidity;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.List;

public final class LeadSpecifications {

    private LeadSpecifications() {
    }

    public static Specification<Lead> search(String search) {

        if (search == null || search.isBlank()) {
            return null;
        }

        String like = "%" + search.toLowerCase() + "%";

        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("companyName")), like),
                cb.like(cb.lower(root.get("contactPerson")), like),
                cb.like(cb.lower(root.get("phone")), like),
                cb.like(cb.lower(root.get("email")), like)
        );
    }

    public static Specification<Lead> hasStatus(LeadStatus status) {

        if (status == null) {
            return null;
        }

        return (root, query, cb) -> cb.equal(root.get("leadStatus"), status);
    }

    public static Specification<Lead> hasLeadSourceId(Long leadSourceId) {

        if (leadSourceId == null) {
            return null;
        }

        return (root, query, cb) -> cb.equal(root.get("leadSource").get("id"), leadSourceId);
    }

    public static Specification<Lead> hasIndustryId(Long industryId) {

        if (industryId == null) {
            return null;
        }

        return (root, query, cb) -> cb.equal(root.get("industry").get("id"), industryId);
    }

    public static Specification<Lead> hasAssignedEmployeeId(Long employeeId) {

        if (employeeId == null) {
            return null;
        }

        return (root, query, cb) -> cb.equal(root.get("assignedEmployee").get("id"), employeeId);
    }

    public static Specification<Lead> hasCity(String city) {

        if (city == null || city.isBlank()) {
            return null;
        }

        return (root, query, cb) -> cb.equal(cb.lower(root.get("city")), city.toLowerCase());
    }

    public static Specification<Lead> hasState(String state) {

        if (state == null || state.isBlank()) {
            return null;
        }

        return (root, query, cb) -> cb.equal(cb.lower(root.get("state")), state.toLowerCase());
    }

    public static Specification<Lead> hasLeadValidity(LeadValidity leadValidity) {

        if (leadValidity == null) {
            return null;
        }

        return (root, query, cb) -> cb.equal(root.get("leadValidity"), leadValidity);
    }

    public static Specification<Lead> hasProductId(Long productId) {

        if (productId == null) {
            return null;
        }

        return (root, query, cb) -> {

            Subquery<Long> subquery = query.subquery(Long.class);
            var lp = subquery.from(LeadProduct.class);

            subquery.select(lp.get("id"));
            subquery.where(
                    cb.equal(lp.get("lead"), root),
                    cb.equal(lp.get("product").get("id"), productId)
            );

            return cb.exists(subquery);
        };
    }

    public static Specification<Lead> hasBatteryId(Long batteryId) {

        if (batteryId == null) {
            return null;
        }

        return (root, query, cb) -> {

            Subquery<Long> subquery = query.subquery(Long.class);
            var lb = subquery.from(LeadBattery.class);

            subquery.select(lb.get("id"));
            subquery.where(
                    cb.equal(lb.get("lead"), root),
                    cb.equal(lb.get("battery").get("id"), batteryId)
            );

            return cb.exists(subquery);
        };
    }

    public static Specification<Lead> createdBetween(LocalDateTime from, LocalDateTime to) {

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

    // RBAC scope filter - null employeeIds means ALL (no filter), otherwise
    // the record's owner must be one of the visible ids. Combined via AND
    // with any explicit assignedEmployeeId filter, so a caller can never
    // widen their own visibility by requesting an out-of-scope employee id:
    // the AND of "owner in my visible set" and "owner = someone else's id"
    // is simply never true.
    public static Specification<Lead> ownerIn(List<Long> employeeIds) {

        if (employeeIds == null) {
            return null;
        }

        return (root, query, cb) -> root.get("assignedEmployee").get("id").in(employeeIds);
    }

    public static Specification<Lead> hasIds(List<Long> ids) {

        if (ids == null) {
            return null;
        }

        return (root, query, cb) -> root.get("id").in(ids);
    }
}
