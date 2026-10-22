package com.adesa.attendance.employee.dto;

import com.adesa.attendance.employee.EmployeeRole;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * Partial update of an employee account. All fields optional; only provided
 * fields are changed. {@code isActive=false} deactivates the account.
 */
public record UpdateEmployeeRequest(
        @Size(max = 200) String fullName,
        @Email @Size(max = 320) String email,
        EmployeeRole role,
        Boolean isActive,
        @Size(max = 128) String password) {
}
