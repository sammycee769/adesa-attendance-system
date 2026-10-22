package com.adesa.attendance.accounttoken.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Completes the account-setup or password-recovery flow with a token.
 *
 * <p>Length is validated by the shared {@code PasswordPolicy}, not here, so the
 * frontend and backend always agree on the message.
 */
public record ResetPasswordRequest(
        @NotBlank @Size(max = 512) String token,
        @NotBlank @Size(max = 128) String newPassword,
        @NotBlank @Size(max = 128) String confirmPassword) {
}
