package com.compact.crm.repository;

import com.compact.crm.entity.Customer;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Opportunity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findByOpportunity(Opportunity opportunity);

    @Query("""
        SELECT c
        FROM Customer c
        WHERE
            (:employee IS NULL OR c.opportunity.lead.assignedEmployee = :employee)
        AND
        (
            LOWER(c.companyName) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(c.contactPerson) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(c.phone) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(c.email) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(c.customerCode) LIKE LOWER(CONCAT('%', :search, '%'))
        )
    """)
    Page<Customer> searchCustomers(
            @Param("employee") Employee employee,
            @Param("search") String search,
            Pageable pageable
    );

}