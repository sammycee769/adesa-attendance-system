package com.adesa.attendance.attendance.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A manual correction of an attendance record.
 *
 * <p>The reason is mandatory: without it a correction is indistinguishable
 * from a silent overwrite. {@code signOutAt} may be null to reopen a workday
 * that was signed out by mistake.
 */
public record AttendanceCorrectionRequest(
        @NotNull Instant signInAt,
        Instant signOutAt,
        @NotBlank @Size(min = 10, max = 500) String reason) {
}
