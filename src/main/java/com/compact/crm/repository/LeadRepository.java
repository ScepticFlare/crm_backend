package com.compact.crm.repository;

import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Lead;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;
public interface LeadRepository extends JpaRepository<Lead, Long> {

    @Query("""
        SELECT l
        FROM Lead l
        WHERE
            (:employee IS NULL OR l.assignedEmployee = :employee)
        AND
        (
            LOWER(l.companyName) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(l.contactPerson) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(l.phone) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(l.email) LIKE LOWER(CONCAT('%', :search, '%'))
        )
    """)
    Page<Lead> searchLeads(
            @Param("employee") Employee employee,
            @Param("search") String search,
            Pageable pageable
    );

    boolean existsByPhone(String phone);

    boolean existsByEmail(String email);
    List<Lead> findByCreatedAtBetween(
            LocalDateTime from,
            LocalDateTime to
    );

    List<Lead> findByAssignedEmployeeAndCreatedAtBetween(
            Employee employee,
            LocalDateTime from,
            LocalDateTime to
    );

}
