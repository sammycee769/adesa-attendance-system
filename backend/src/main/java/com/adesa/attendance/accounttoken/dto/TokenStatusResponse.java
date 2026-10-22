package com.adesa.attendance.accounttoken.dto;

/**
 * The result of validating a setup/recovery link before the form is shown.
 *
 * @param valid   true when the token can still be used
 * @param type    the flow the token belongs to ({@code ACCOUNT_SETUP} or
 *                {@code PASSWORD_RECOVERY}), or null when invalid
 * @param reason  a machine-readable reason when invalid
 *                ({@code INVALID_OR_EXPIRED}), or null when valid
 */
public record TokenStatusResponse(boolean valid, String type, String reason) {

    public static TokenStatusResponse valid(String type) {
        return new TokenStatusResponse(true, type, null);
    }

    public static TokenStatusResponse invalid() {
        return new TokenStatusResponse(false, null, "INVALID_OR_EXPIRED");
    }
}
