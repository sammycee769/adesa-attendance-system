package com.adesa.attendance.device.dto;

import java.time.Instant;

import com.adesa.attendance.device.RegisteredDevice;

/**
 * Employee-facing view of their device registration. Never exposes credential
 * ids, public keys or sign counters.
 */
public record DeviceStatusResponse(
        String status,
        String label,
        Instant registeredAt,
        Instant approvedAt,
        Instant revokedAt,
        String revokedReason) {

    /** Status used when the employee has never registered a device. */
    public static DeviceStatusResponse none() {
        return new DeviceStatusResponse("NONE", null, null, null, null, null);
    }

    public static DeviceStatusResponse from(RegisteredDevice device) {
        return new DeviceStatusResponse(
                device.getStatus().name(),
                device.getLabel(),
                device.getCreatedAt(),
                device.getApprovedAt(),
                device.getRevokedAt(),
                device.getRevokedReason());
    }
}
