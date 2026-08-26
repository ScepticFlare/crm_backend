package com.compact.crm.specification;

import com.compact.crm.entity.ActivityLog;
import com.compact.crm.enums.ActivityAction;
import com.compact.crm.enums.ActivityModule;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.List;

public final class ActivityLogSpecifications {

    private ActivityLogSpecifications() {
    }

    public static Specification<ActivityLog> hasModule(ActivityModule module) {

        if (module == null) {
            return null;
        }

        return (root, query, cb) -> cb.equal(root.get("module"), module);
    }

    public static Specification<ActivityLog> hasAction(ActivityAction action) {

        if (action == null) {
            return null;
        }

        return (root, query, cb) -> cb.equal(root.get("action"), action);
    }

    // Opposite of hasAction - excludes one action rather than matching it.
    // Backs the Lead Details "Important Activity" view (excludeAction=VIEW):
    // repeated VIEW entries are hidden from that default view without ever
    // being deleted or skipped at write time.
    public static Specification<ActivityLog> excludingAction(ActivityAction action) {

        if (action == null) {
            return null;
        }

        return (root, query, cb) -> cb.notEqual(root.get("action"), action);
    }

    // Backs "history for this one Lead/Opportunity/Customer/FollowUp"
    // (e.g. GET /api/activity?module=LEAD&entityId=123) - entityId was
    // already stored on every row, just never filterable until now.
    public static Specification<ActivityLog> hasEntityId(Long entityId) {

        if (entityId == null) {
            return null;
        }

        return (root, query, cb) -> cb.equal(root.get("entityId"), entityId);
    }

    public static Specification<ActivityLog> createdBetween(LocalDateTime from, LocalDateTime to) {

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
    // the row's actor must be one of the visible ids. Combined via AND with
    // any explicit employeeId filter (see hasEmployeeId), so a caller can
    // never widen their own visibility by requesting an out-of-scope
    // employee id: the AND of "actor in my visible set" and "actor =
    // someone else's id" is simply never true.
    public static Specification<ActivityLog> actorIn(List<Long> employeeIds) {

        if (employeeIds == null) {
            return null;
        }

        return (root, query, cb) -> root.get("employee").get("id").in(employeeIds);
    }

    public static Specification<ActivityLog> hasEmployeeId(Long employeeId) {

        if (employeeId == null) {
            return null;
        }

        return (root, query, cb) -> cb.equal(root.get("employee").get("id"), employeeId);
    }
}
