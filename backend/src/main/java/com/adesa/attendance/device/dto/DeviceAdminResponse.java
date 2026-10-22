package com.adesa.attendance.device.dto;

import java.time.Instant;
import java.util.UUID;

import com.adesa.attendance.device.RegisteredDevice;

/** Administrator view of a registered device. Never exposes credential data. */
public record DeviceAdminResponse(
        UUID id,
        UUID employeeId,
        String employeeNumber,
        String employeeName,
        String label,
        String status,
        Instant createdAt,
        Instant approvedAt,
        Instant revokedAt,
        String revokedReason) {

    public static DeviceAdminResponse from(RegisteredDevice device, String employeeNumber, String employeeName) {
        return new DeviceAdminResponse(
                device.getId(),
                device.getEmployeeId(),
                employeeNumber,
                employeeName,
                device.getLabel(),
                device.getStatus().name(),
                device.getCreatedAt(),
                device.getApprovedAt(),
                device.getRevokedAt(),
                device.getRevokedReason());
    }
}
