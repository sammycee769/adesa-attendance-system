package com.adesa.attendance.accounttoken.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Authenticated password change. The current password is always required. */
public record ChangePasswordRequest(
        @NotBlank @Size(max = 128) String currentPassword,
        @NotBlank @Size(max = 128) String newPassword,
        @NotBlank @Size(max = 128) String confirmPassword) {
}
