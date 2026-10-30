package com.compact.crm.specification;

import com.compact.crm.entity.Lead;
import com.compact.crm.entity.LeadBattery;
import com.compact.crm.entity.LeadProduct;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.LeadValidity;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.Collection;
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

    // Multi-value filters: a lead matches when it has ANY of the selected
    // values (OR within one filter); different filters are AND-ed by
    // SpecificationUtil.and. A null / empty selection means "no restriction".

    public static Specification<Lead> hasStatus(Collection<LeadStatus> statuses) {

        List<LeadStatus> values = SpecificationUtil.selected(statuses);

        if (values == null) {
            return null;
        }

        return (root, query, cb) -> root.get("leadStatus").in(values);
    }

    public static Specification<Lead> hasLeadSourceId(Collection<Long> leadSourceIds) {

        List<Long> ids = SpecificationUtil.selected(leadSourceIds);

        if (ids == null) {
            return null;
        }

        return (root, query, cb) -> root.get("leadSource").get("id").in(ids);
    }

    public static Specification<Lead> hasIndustryId(Collection<Long> industryIds) {

        List<Long> ids = SpecificationUtil.selected(industryIds);

        if (ids == null) {
            return null;
        }

        return (root, query, cb) -> root.get("industry").get("id").in(ids);
    }

    public static Specification<Lead> hasAssignedEmployeeId(Collection<Long> employeeIds) {

        List<Long> ids = SpecificationUtil.selected(employeeIds);

        if (ids == null) {
            return null;
        }

        return (root, query, cb) -> root.get("assignedEmployee").get("id").in(ids);
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

    public static Specification<Lead> hasLeadValidity(Collection<LeadValidity> validities) {

        List<LeadValidity> values = SpecificationUtil.selected(validities);

        if (values == null) {
            return null;
        }

        return (root, query, cb) -> root.get("leadValidity").in(values);
    }

    // EXISTS keeps one row per lead even when several line items match.
    public static Specification<Lead> hasProductId(Collection<Long> productIds) {

        List<Long> ids = SpecificationUtil.selected(productIds);

        if (ids == null) {
            return null;
        }

        return (root, query, cb) -> {

            Subquery<Long> subquery = query.subquery(Long.class);
            var lp = subquery.from(LeadProduct.class);

            subquery.select(lp.get("id"));
            subquery.where(
                    cb.equal(lp.get("lead"), root),
                    lp.get("product").get("id").in(ids)
            );

            return cb.exists(subquery);
        };
    }

    // EXISTS keeps one row per lead even when several line items match.
    public static Specification<Lead> hasBatteryId(Collection<Long> batteryIds) {

        List<Long> ids = SpecificationUtil.selected(batteryIds);

        if (ids == null) {
            return null;
        }

        return (root, query, cb) -> {

            Subquery<Long> subquery = query.subquery(Long.class);
            var lb = subquery.from(LeadBattery.class);

            subquery.select(lb.get("id"));
            subquery.where(
                    cb.equal(lb.get("lead"), root),
                    lb.get("battery").get("id").in(ids)
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

    // "All matching records except the ones the user unticked" - see the
    // export's excludeIds. Null / empty excludes nothing.
    public static Specification<Lead> notInIds(Collection<Long> ids) {

        List<Long> excluded = SpecificationUtil.selected(ids);

        if (excluded == null) {
            return null;
        }

        return (root, query, cb) -> cb.not(root.get("id").in(excluded));
    }

    // Every Lead returned to the frontend gets serialized whole (see
    // controller.LeadController.getAllLeads), so Jackson always walks
    // industry/leadSource/assignedEmployee. Left un-fetched, each of those
    // three EAGER to-one associations is a separate SELECT per row when the
    // list is loaded via a Criteria/Specification query (they only get
    // folded into the main query automatically for a plain findById) - up
    // to 150 extra queries for a 50-row page. Explicitly fetching them here
    // folds all three into the single main query as LEFT JOINs instead.
    // Skipped on the count query Spring Data also runs for pagination
    // (fetches there would be pointless and some JPA providers reject them
    // outright on a query with a non-entity result type).
    public static Specification<Lead> fetchAssociations() {

        return (root, query, cb) -> {

            if (query.getResultType() != Long.class && query.getResultType() != long.class) {
                root.fetch("industry", JoinType.LEFT);
                root.fetch("leadSource", JoinType.LEFT);
                root.fetch("assignedEmployee", JoinType.LEFT);
            }

            return cb.conjunction();
        };
    }

    // ---- possible-duplicate detection (warning only) ----

    // Leads that share a normalized phone (phone or alternate phone), email
    // (email or secondary email) or company name with the given values.
    // Any blank/absent input is simply not matched on - an all-blank call
    // matches nothing.
    public static Specification<Lead> possibleDuplicateOf(String phone, String email, String company, Long excludeId) {

        return (root, query, cb) -> {

            java.util.List<jakarta.persistence.criteria.Predicate> any = new java.util.ArrayList<>();

            if (phone != null) {
                any.add(cb.equal(root.get("phone"), phone));
                any.add(cb.equal(root.get("alternatePhone"), phone));
            }

            if (email != null) {
                any.add(cb.equal(cb.lower(cb.trim(root.<String>get("email"))), email));
                any.add(cb.equal(cb.lower(cb.trim(root.<String>get("secondaryEmail"))), email));
            }

            if (company != null) {
                any.add(cb.equal(cb.lower(cb.trim(root.<String>get("companyName"))), company));
            }

            if (any.isEmpty()) {
                return cb.disjunction();
            }

            jakarta.persistence.criteria.Predicate match = cb.or(any.toArray(new jakarta.persistence.criteria.Predicate[0]));

            return excludeId == null ? match : cb.and(match, cb.notEqual(root.get("id"), excludeId));
        };
    }

    // Lifecycle categories (enums.LeadCategory) - mutually exclusive, so a
    // lead matches exactly one. Conversion is the opportunities.lead_id
    // relationship, never a status label. A null/empty selection means "no
    // restriction" (every category).
    public static Specification<com.compact.crm.entity.Lead> inCategories(
            java.util.Set<com.compact.crm.enums.LeadCategory> categories) {

        if (categories == null) {
            return null;
        }

        if (categories.isEmpty()) {
            return (root, query, cb) -> cb.disjunction();
        }

        return (root, query, cb) -> {

            Subquery<Long> sub = query.subquery(Long.class);
            jakarta.persistence.criteria.Root<com.compact.crm.entity.Opportunity> opp =
                    sub.from(com.compact.crm.entity.Opportunity.class);
            sub.select(opp.<Long>get("id")).where(cb.equal(opp.get("lead").get("id"), root.get("id")));

            jakarta.persistence.criteria.Predicate converted = cb.exists(sub);
            // NULL-safe negations: a lead with no stored status is NEW
            // (LeadWorkflow.effectiveStatus), and "NOT (NULL = x)" would
            // otherwise be unknown and drop the row from every category.
            jakarta.persistence.criteria.Predicate isInvalid = cb.equal(root.get("leadStatus"), LeadStatus.INVALID);
            jakarta.persistence.criteria.Predicate notInvalid =
                    cb.or(cb.isNull(root.get("leadStatus")), cb.notEqual(root.get("leadStatus"), LeadStatus.INVALID));
            jakarta.persistence.criteria.Predicate isValid = cb.equal(root.get("leadStatus"), LeadStatus.VALID);
            jakarta.persistence.criteria.Predicate notValid =
                    cb.or(cb.isNull(root.get("leadStatus")), cb.notEqual(root.get("leadStatus"), LeadStatus.VALID));

            List<jakarta.persistence.criteria.Predicate> any = new java.util.ArrayList<>();

            for (com.compact.crm.enums.LeadCategory category : categories) {
                any.add(switch (category) {
                    case INVALID -> isInvalid;
                    case CONVERTED -> cb.and(notInvalid, converted);
                    case VALID_UNCONVERTED -> cb.and(notInvalid, cb.not(converted), isValid);
                    case NEW -> cb.and(notInvalid, cb.not(converted), notValid);
                });
            }

            return cb.or(any.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }

    // The display-status groups of the simplified lead workflow, evaluated
    // server-side so a legacy-status lead is found under the group it is
    // SHOWN under (see service.LeadWorkflow.effectiveStatus):
    //   INVALID - stored INVALID;
    //   VALID   - stored VALID, or already converted (an Opportunity exists);
    //   NEW     - everything else.
    // Any other value is ignored (no filter).
    // Several display groups: a lead in ANY of them. Values other than
    // NEW / VALID / INVALID are ignored, as for a single group.
    public static Specification<Lead> inStatusGroups(Collection<LeadStatus> groups) {

        List<LeadStatus> selected = SpecificationUtil.selected(groups);

        if (selected == null) {
            return null;
        }

        List<Specification<Lead>> specs = selected.stream()
                .map(LeadSpecifications::inStatusGroup)
                .filter(java.util.Objects::nonNull)
                .toList();

        return specs.isEmpty() ? null : Specification.anyOf(specs);
    }

    public static Specification<Lead> inStatusGroup(LeadStatus group) {

        if (group != LeadStatus.NEW && group != LeadStatus.VALID && group != LeadStatus.INVALID) {
            return null;
        }

        return (root, query, cb) -> {

            Subquery<Long> sub = query.subquery(Long.class);
            jakarta.persistence.criteria.Root<com.compact.crm.entity.Opportunity> opp =
                    sub.from(com.compact.crm.entity.Opportunity.class);
            sub.select(opp.<Long>get("id")).where(cb.equal(opp.get("lead").get("id"), root.get("id")));

            jakarta.persistence.criteria.Predicate converted = cb.exists(sub);
            jakarta.persistence.criteria.Predicate isInvalid = cb.equal(root.get("leadStatus"), LeadStatus.INVALID);
            jakarta.persistence.criteria.Predicate isValid = cb.equal(root.get("leadStatus"), LeadStatus.VALID);

            return switch (group) {
                case INVALID -> isInvalid;
                case VALID -> cb.and(cb.not(isInvalid), cb.or(isValid, converted));
                default -> cb.and(cb.not(isInvalid), cb.not(isValid), cb.not(converted));
            };
        };
    }

    // ---- stale-lead detection (READ-ONLY; see service.StaleLeadRules) ----

    // A lead with an open status, not edited since the cutoff, no follow-up
    // created/updated since the cutoff, and no Opportunity.
    public static Specification<Lead> stale(LocalDateTime cutoff) {

        return (root, query, cb) -> {

            Subquery<Long> recentFollowUp = query.subquery(Long.class);
            jakarta.persistence.criteria.Root<com.compact.crm.entity.FollowUp> f =
                    recentFollowUp.from(com.compact.crm.entity.FollowUp.class);
            recentFollowUp.select(f.<Long>get("id")).where(
                    cb.equal(f.get("lead").get("id"), root.get("id")),
                    cb.or(
                            cb.greaterThanOrEqualTo(f.<LocalDateTime>get("updatedAt"), cutoff),
                            cb.greaterThanOrEqualTo(f.<LocalDateTime>get("createdAt"), cutoff)
                    )
            );

            Subquery<Long> opportunity = query.subquery(Long.class);
            jakarta.persistence.criteria.Root<com.compact.crm.entity.Opportunity> o =
                    opportunity.from(com.compact.crm.entity.Opportunity.class);
            opportunity.select(o.<Long>get("id")).where(cb.equal(o.get("lead").get("id"), root.get("id")));

            return cb.and(
                    root.get("leadStatus").in(com.compact.crm.service.StaleLeadRules.OPEN_STATUSES),
                    cb.lessThan(root.<LocalDateTime>get("updatedAt"), cutoff),
                    cb.not(cb.exists(recentFollowUp)),
                    cb.not(cb.exists(opportunity))
            );
        };
    }

    // The Inactive Leads page: leads whose STORED status is the historical
    // INACTIVE, OR that are merely stale right now. One query with OR, so a
    // lead that satisfies both appears once.
    public static Specification<Lead> inactiveView(LocalDateTime cutoff) {

        Specification<Lead> stale = stale(cutoff);

        return (root, query, cb) -> cb.or(
                cb.equal(root.get("leadStatus"), LeadStatus.INACTIVE),
                stale.toPredicate(root, query, cb)
        );
    }
}
