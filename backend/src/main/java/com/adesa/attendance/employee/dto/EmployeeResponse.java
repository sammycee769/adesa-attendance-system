package com.adesa.attendance.employee.dto;

import java.time.Instant;
import java.util.UUID;

import com.adesa.attendance.employee.Employee;
import com.adesa.attendance.employee.EmployeeRole;

/** Employee data returned by the API. Never includes password material. */
public record EmployeeResponse(
        UUID id,
        String employeeNumber,
        String fullName,
        String email,
        EmployeeRole role,
        boolean isActive,
        boolean mustChangePassword,
        Instant createdAt,
        Instant updatedAt) {

    public static EmployeeResponse from(Employee employee) {
        return new EmployeeResponse(
                employee.getId(),
                employee.getEmployeeNumber(),
                employee.getFullName(),
                employee.getEmail(),
                employee.getRole(),
                employee.isActive(),
                employee.isMustChangePassword(),
                employee.getCreatedAt(),
                employee.getUpdatedAt());
    }
}
