package com.adesa.attendance.accounttoken;

/** The two flows backed by a single-use {@link AccountToken}. */
public enum AccountTokenType {
    /** Emailed when an administrator creates an account or forces a reset. */
    ACCOUNT_SETUP,
    /** Emailed from the "forgot password" form. */
    PASSWORD_RECOVERY
}
