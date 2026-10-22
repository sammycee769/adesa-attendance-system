package com.adesa.attendance.accounttoken;

import java.util.Set;

import com.adesa.attendance.common.BadRequestException;
import com.adesa.attendance.config.AccountProperties;

import org.springframework.stereotype.Component;

/**
 * The single password-strength rule, applied identically wherever a password
 * is set (account setup, recovery, authenticated change, administrator reset).
 *
 * <p>Rule: at least {@code adesa.account.password-min-length} characters
 * (default 12) and must contain at least one letter and one digit. A short
 * built-in denylist rejects the most obvious weak choices. The frontend mirrors
 * the same message so the two can never disagree in wording.
 */
@Component
public class PasswordPolicy {

    /**
     * The smallest set of passwords that must never be accepted. This is a
     * guard against the most common choices, not a substitute for length.
     */
    private static final Set<String> WEAK = Set.of(
            "password", "password1", "password12", "password123", "password1234",
            "passw0rd", "passw0rd1", "passw0rd12", "123456789012", "qwertyuiop12",
            "administrator", "administrator1", "adesa1234567", "adesapassword",
            "letmein12345", "welcome12345", "changeme1234", "iloveyou1234");

    private final AccountProperties accountProperties;

    public PasswordPolicy(AccountProperties accountProperties) {
        this.accountProperties = accountProperties;
    }

    /** The message shown when a password is too weak, or {@code null} when valid. */
    public String validate(String password) {
        int min = accountProperties.getPasswordMinLength();
        int max = accountProperties.getPasswordMaxLength();
        if (password == null || password.isEmpty()) {
            return "A password is required.";
        }
        if (password.length() < min) {
            return "The password must have at least " + min + " characters.";
        }
        if (password.length() > max) {
            return "The password must have at most " + max + " characters.";
        }
        if (WEAK.contains(password.toLowerCase(java.util.Locale.ROOT))) {
            return "This password is too common. Choose a different one.";
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            return "The password must contain at least one letter and one digit.";
        }
        return null;
    }

    /** Throws {@link BadRequestException} when the password is too weak. */
    public void enforce(String password) {
        String message = validate(password);
        if (message != null) {
            throw new BadRequestException(message);
        }
    }

    public int minLength() {
        return accountProperties.getPasswordMinLength();
    }
}
