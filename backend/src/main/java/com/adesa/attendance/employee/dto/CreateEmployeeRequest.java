package com.adesa.attendance.employee.dto;

import com.adesa.attendance.employee.EmployeeRole;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request to create an employee account. Only reachable by ADMIN.
 *
 * <p>The password is <strong>optional</strong>: the recommended flow creates
 * the account with no password and e-mails the employee a one-time setup link
 * so they choose their own. An administrator may still supply an initial
 * password for in-person onboarding; the account is then created with
 * {@code mustChangePassword = true} and must change it at first sign-in either
 * way.
 */
public record CreateEmployeeRequest(
        @NotBlank @Size(max = 32) String employeeNumber,
        @NotBlank @Size(max = 200) String fullName,
        @NotBlank @Email @Size(max = 320) String email,
        @Size(max = 128) String password,
        @NotNull EmployeeRole role) {
}
