package com.compact.crm.specification;

import com.compact.crm.entity.Customer;
import jakarta.persistence.criteria.Fetch;
import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.List;

public final class CustomerSpecifications {

    private CustomerSpecifications() {
    }

    public static Specification<Customer> search(String search) {

        if (search == null || search.isBlank()) {
            return null;
        }

        String like = "%" + search.toLowerCase() + "%";

        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("companyName")), like),
                cb.like(cb.lower(root.get("contactPerson")), like),
                cb.like(cb.lower(root.get("phone")), like),
                cb.like(cb.lower(root.get("email")), like),
                cb.like(cb.lower(root.get("customerCode")), like)
        );
    }

    public static Specification<Customer> hasAssignedEmployeeId(Long employeeId) {

        if (employeeId == null) {
            return null;
        }

        return (root, query, cb) -> cb.equal(root.get("assignedEmployee").get("id"), employeeId);
    }

    public static Specification<Customer> hasIndustryId(Long industryId) {

        if (industryId == null) {
            return null;
        }

        return (root, query, cb) ->
                cb.equal(root.get("opportunity").get("lead").get("industry").get("id"), industryId);
    }

    public static Specification<Customer> hasCity(String city) {

        if (city == null || city.isBlank()) {
            return null;
        }

        return (root, query, cb) -> cb.equal(cb.lower(root.get("city")), city.toLowerCase());
    }

    public static Specification<Customer> hasState(String state) {

        if (state == null || state.isBlank()) {
            return null;
        }

        return (root, query, cb) -> cb.equal(cb.lower(root.get("state")), state.toLowerCase());
    }

    public static Specification<Customer> createdBetween(LocalDateTime from, LocalDateTime to) {

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

    // RBAC scope - Customer carries its own assignedEmployee (the single
    // source of truth fixed during the RBAC hierarchy work), used both
    // here and by CustomerService.getAuthorizedCustomer.
    public static Specification<Customer> ownerIn(List<Long> employeeIds) {

        if (employeeIds == null) {
            return null;
        }

        return (root, query, cb) -> root.get("assignedEmployee").get("id").in(employeeIds);
    }

    public static Specification<Customer> hasIds(List<Long> ids) {

        if (ids == null) {
            return null;
        }

        return (root, query, cb) -> root.get("id").in(ids);
    }

    // Customer is returned to the frontend whole (controller.
    // CustomerController), and Customer.opportunity - a @OneToOne, EAGER by
    // default - drags in the *entire* Opportunity graph (salesStage, its
    // lead, and that lead's industry/leadSource/assignedEmployee) even
    // though pages/Customers.jsx only ever displays assignedEmployee.name.
    // Left un-fetched, every one of those associations is a separate query
    // per row. All single-valued, so one LEFT JOIN chain fetches the whole
    // thing in the main query with no cartesian row multiplication; skipped
    // on the count query, same as LeadSpecifications.fetchAssociations.
    public static Specification<Customer> fetchAssociations() {

        return (root, query, cb) -> {

            if (query.getResultType() != Long.class && query.getResultType() != long.class) {
                root.fetch("assignedEmployee", JoinType.LEFT);
                Fetch<Customer, ?> oppFetch = root.fetch("opportunity", JoinType.LEFT);
                oppFetch.fetch("salesStage", JoinType.LEFT);
                Fetch<?, ?> leadFetch = oppFetch.fetch("lead", JoinType.LEFT);
                leadFetch.fetch("industry", JoinType.LEFT);
                leadFetch.fetch("leadSource", JoinType.LEFT);
                leadFetch.fetch("assignedEmployee", JoinType.LEFT);
            }

            return cb.conjunction();
        };
    }
}
