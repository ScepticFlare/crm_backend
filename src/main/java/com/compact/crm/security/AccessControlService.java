package com.compact.crm.security;

import com.compact.crm.entity.Employee;
import com.compact.crm.entity.RolePermission;
import com.compact.crm.enums.Scope;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.RolePermissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Single place every module asks "can this employee do X, and over whose
 * records" - so no service ever branches on {@code role == MANAGER} etc.
 * directly. Two things are resolved here, on purpose kept separate:
 *
 * <ul>
 *   <li>Permission: does the employee's Role grant the given permission
 *       code at all (role_permissions, seeded by migration).</li>
 *   <li>Team membership: resolved independently from the Employee.manager
 *       hierarchy, one level deep - a Manager's team is themselves plus
 *       their direct reports, never another manager's reports.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AccessControlService {

    // Permission codes. Kept as plain constants (not an enum) so new ones
    // can be added purely as seed data + a call site, without touching this
    // class.
    public static final String LEAD_VIEW = "LEAD_VIEW";
    public static final String LEAD_MANAGE = "LEAD_MANAGE";
    public static final String OPPORTUNITY_VIEW = "OPPORTUNITY_VIEW";
    public static final String OPPORTUNITY_MANAGE = "OPPORTUNITY_MANAGE";
    public static final String CUSTOMER_VIEW = "CUSTOMER_VIEW";
    public static final String CUSTOMER_MANAGE = "CUSTOMER_MANAGE";
    public static final String FOLLOWUP_VIEW = "FOLLOWUP_VIEW";
    public static final String FOLLOWUP_MANAGE = "FOLLOWUP_MANAGE";
    public static final String EMPLOYEE_MANAGE = "EMPLOYEE_MANAGE";
    // Listing the employee roster (e.g. to populate an "Assigned Employee"
    // picker) is a lighter-weight capability than EMPLOYEE_MANAGE: ADMIN
    // sees everyone (ALL), MANAGER sees their own team (TEAM) so they can
    // reassign Leads/Customers within it, EMPLOYEE has no grant (403,
    // unchanged from before this permission existed).
    public static final String EMPLOYEE_VIEW = "EMPLOYEE_VIEW";
    // Visibility into the Employee Activity/Audit Log - same ALL/TEAM/OWN
    // scope model as everything else (see V4 migration), kept separate
    // from EMPLOYEE_VIEW/EMPLOYEE_MANAGE since "who can see the employee
    // roster" and "who can see activity history" are different questions.
    public static final String ACTIVITY_VIEW = "ACTIVITY_VIEW";

    // Deletion and export are deliberately split out of *_MANAGE (which
    // still governs create/update/reassign) into their own admin-only
    // permission codes - see V5 migration. MANAGE stays TEAM/OWN for
    // Manager/Employee exactly as before; only ADMIN is ever granted these.
    public static final String LEAD_DELETE = "LEAD_DELETE";
    public static final String OPPORTUNITY_DELETE = "OPPORTUNITY_DELETE";
    public static final String CUSTOMER_DELETE = "CUSTOMER_DELETE";
    public static final String FOLLOWUP_DELETE = "FOLLOWUP_DELETE";
    public static final String LEAD_EXPORT = "LEAD_EXPORT";
    public static final String OPPORTUNITY_EXPORT = "OPPORTUNITY_EXPORT";
    public static final String CUSTOMER_EXPORT = "CUSTOMER_EXPORT";
    public static final String FOLLOWUP_EXPORT = "FOLLOWUP_EXPORT";

    private static final String NOT_AUTHORIZED = "You are not authorized to access this record.";
    private static final String NO_PERMISSION = "You do not have permission to perform this action.";

    private final RolePermissionRepository rolePermissionRepository;
    private final EmployeeRepository employeeRepository;

    /**
     * The scope (OWN/TEAM/ALL) the employee's role grants for this
     * permission, or {@code null} if the role doesn't grant it at all.
     */
    public Scope resolveScope(Employee employee, String permissionCode) {

        return rolePermissionRepository
                .findByRole_IdAndPermission_Code(employee.getRole().getId(), permissionCode)
                .map(RolePermission::getScope)
                .orElse(null);
    }

    public boolean hasPermission(Employee employee, String permissionCode) {
        return resolveScope(employee, permissionCode) != null;
    }

    /**
     * Self plus direct reports (one level - see class docs). Always
     * includes the employee themselves, so TEAM scope covers "my own
     * records" without a separate OWN check.
     */
    public List<Long> teamMemberIds(Employee employee) {

        List<Long> ids = new ArrayList<>();
        ids.add(employee.getId());

        employeeRepository.findByManagerId(employee.getId())
                .forEach(report -> ids.add(report.getId()));

        return ids;
    }

    /**
     * Resolves the employee-id filter a list query should apply for this
     * permission: {@code null} means "no filter" (ALL scope), otherwise the
     * exact set of employee ids whose records are visible. Throws if the
     * role has no grant for this permission at all.
     */
    public List<Long> resolveVisibleEmployeeIds(Employee employee, String permissionCode) {

        Scope scope = resolveScope(employee, permissionCode);

        if (scope == null) {
            throw new AccessDeniedException(NO_PERMISSION);
        }

        return switch (scope) {
            case ALL -> null;
            case TEAM -> teamMemberIds(employee);
            case OWN -> List.of(employee.getId());
        };
    }

    /**
     * Single-record check: does the employee's grant for this permission
     * cover a record owned by {@code ownerEmployeeId}. Throws
     * AccessDeniedException otherwise - including when the role has no
     * grant for the permission at all.
     */
    public void assertCanAccess(Employee employee, String permissionCode, Long ownerEmployeeId) {

        Scope scope = resolveScope(employee, permissionCode);

        if (scope == null) {
            throw new AccessDeniedException(NO_PERMISSION);
        }

        if (scope == Scope.ALL) {
            return;
        }

        if (scope == Scope.OWN) {
            if (ownerEmployeeId == null || !employee.getId().equals(ownerEmployeeId)) {
                throw new AccessDeniedException(NOT_AUTHORIZED);
            }
            return;
        }

        // TEAM
        if (ownerEmployeeId == null || !teamMemberIds(employee).contains(ownerEmployeeId)) {
            throw new AccessDeniedException(NOT_AUTHORIZED);
        }
    }

    /**
     * Hard gate for an action with no per-record ownership dimension to
     * check (e.g. a bulk operation before it starts, or an export before
     * the result set is even built) - throws unless the employee's role has
     * any grant at all for this permission. For record-scoped actions,
     * prefer {@link #assertCanAccess} instead.
     */
    public void requirePermission(Employee employee, String permissionCode) {

        if (!hasPermission(employee, permissionCode)) {
            throw new AccessDeniedException(NO_PERMISSION);
        }
    }

    /**
     * Whether {@code targetEmployeeId} is a valid reassignment target for
     * the employee's grant of {@code permissionCode}: ALL scope allows any
     * target, TEAM scope only allows targets within the employee's own
     * team, OWN scope (and no grant at all) allows none.
     */
    public boolean canAssignTo(Employee employee, String permissionCode, Long targetEmployeeId) {

        Scope scope = resolveScope(employee, permissionCode);

        if (scope == null || targetEmployeeId == null) {
            return false;
        }

        return switch (scope) {
            case ALL -> true;
            case TEAM -> teamMemberIds(employee).contains(targetEmployeeId);
            case OWN -> false;
        };
    }
}
