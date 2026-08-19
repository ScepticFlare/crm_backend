package com.compact.crm.repository;

import com.compact.crm.entity.Employee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    Optional<Employee> findByEmail(String email);
    Optional<Employee> findByNameIgnoreCase(String name);

    // Direct reports only - a Manager's team is one level deep (see
    // security.AccessControlService.teamMemberIds). Written as an explicit
    // @Query rather than the derived "findByManagerId" form: Spring Data's
    // property-path resolution matches Employee's computed getManagerId()
    // JSON-alias getter as a literal "managerId" property before it tries
    // splitting into manager.id, which then fails to resolve against the
    // JPA metamodel (there is no persistent "managerId" attribute, only
    // the "manager" association).
    @Query("SELECT e FROM Employee e WHERE e.manager.id = :managerId")
    List<Employee> findByManagerId(@Param("managerId") Long managerId);
}