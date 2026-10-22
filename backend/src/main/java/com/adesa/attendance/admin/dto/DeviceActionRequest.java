package com.adesa.attendance.admin.dto;

import jakarta.validation.constraints.Size;

/** Optional reason supplied when an administrator revokes or replaces a device. */
public record DeviceActionRequest(@Size(max = 255) String reason) {
}
