package com.adesa.attendance.employee;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmployeeRepository extends JpaRepository<Employee, UUID>, JpaSpecificationExecutor<Employee> {

    boolean existsByEmployeeNumber(String employeeNumber);

    boolean existsByEmail(String email);

    boolean existsByEmailAndIdNot(String email, UUID id);

    boolean existsByRole(EmployeeRole role);

    boolean existsByRoleAndActiveTrue(EmployeeRole role);

    long countByRoleAndActiveTrue(EmployeeRole role);

    Employee findByEmail(String email);

    /** The active roster, for the daily attendance overview. */
    List<Employee> findByActiveTrueOrderByEmployeeNumberAsc();

    /**
     * Employee ids matching a free-text search over name, email and employee
     * number. Used to turn a report search term into a record filter.
     */
    @Query("select e.id from Employee e where lower(e.fullName) like :pattern "
            + "or lower(e.email) like :pattern or lower(e.employeeNumber) like :pattern")
    List<UUID> findIdsBySearch(@Param("pattern") String pattern);
}
