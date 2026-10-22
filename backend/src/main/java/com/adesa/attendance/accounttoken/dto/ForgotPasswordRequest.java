package com.adesa.attendance.accounttoken.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A "forgot password" request. The e-mail address is never reflected back. */
public record ForgotPasswordRequest(
        @NotBlank @Email @Size(max = 320) String email) {
}
