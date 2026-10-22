package com.adesa.attendance.auth.dto;

import java.util.UUID;

import com.adesa.attendance.device.dto.DeviceStatusResponse;
import com.adesa.attendance.employee.Employee;
import com.adesa.attendance.employee.EmployeeRole;

/**
 * The authenticated identity plus a summary of device state, returned by login
 * and {@code GET /auth/me}. Never includes password or credential material.
 */
public record CurrentUserResponse(
        UUID id,
        String employeeNumber,
        String fullName,
        String email,
        EmployeeRole role,
        boolean mustChangePassword,
        DeviceStatusResponse device) {

    public static CurrentUserResponse from(Employee employee, DeviceStatusResponse device) {
        return new CurrentUserResponse(
                employee.getId(),
                employee.getEmployeeNumber(),
                employee.getFullName(),
                employee.getEmail(),
                employee.getRole(),
                employee.isMustChangePassword(),
                device);
    }
}
