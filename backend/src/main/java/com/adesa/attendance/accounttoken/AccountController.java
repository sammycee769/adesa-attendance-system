package com.adesa.attendance.accounttoken;

import com.adesa.attendance.accounttoken.dto.ChangePasswordRequest;
import com.adesa.attendance.accounttoken.dto.ForgotPasswordRequest;
import com.adesa.attendance.accounttoken.dto.ResetPasswordRequest;
import com.adesa.attendance.accounttoken.dto.TokenStatusResponse;
import com.adesa.attendance.attendance.network.ClientIpResolver;
import com.adesa.attendance.employee.Employee;
import com.adesa.attendance.employee.EmployeeRepository;
import com.adesa.attendance.security.AuthenticatedEmployee;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * Account setup, password recovery and password change.
 *
 * <p>The unauthenticated endpoints answer generically so that they never
 * disclose whether an e-mail address is registered, and they are rate limited
 * per client address.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Account security", description = "Password setup, recovery and change")
public class AccountController {

    private final AccountPasswordService accountPasswordService;
    private final AccountTokenService accountTokenService;
    private final RecoveryRateLimiter recoveryRateLimiter;
    private final ClientIpResolver clientIpResolver;
    private final EmployeeRepository employeeRepository;

    public AccountController(AccountPasswordService accountPasswordService,
                             AccountTokenService accountTokenService,
                             RecoveryRateLimiter recoveryRateLimiter,
                             ClientIpResolver clientIpResolver,
                             EmployeeRepository employeeRepository) {
        this.accountPasswordService = accountPasswordService;
        this.accountTokenService = accountTokenService;
        this.recoveryRateLimiter = recoveryRateLimiter;
        this.clientIpResolver = clientIpResolver;
        this.employeeRepository = employeeRepository;
    }

    /**
     * Requests a password-recovery e-mail.
     *
     * <p>Always answers 202 with the same body whether or not the address is
     * registered, so the endpoint cannot be used to enumerate accounts.
     */
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Request a password-recovery link",
            description = "Always returns 202. Never reveals whether the address is registered. "
                    + "Rate limited per client address.")
    public void forgotPassword(@Valid @RequestBody ForgotPasswordRequest request,
                               HttpServletRequest httpRequest) {
        String key = rateLimitKey(httpRequest);
        if (recoveryRateLimiter.isBlocked(key)) {
            recoveryRateLimiter.recordAttempt(key);
            accountPasswordService.raiseTooManyRequests(recoveryRateLimiter.retryAfterSeconds(key));
        }
        recoveryRateLimiter.recordAttempt(key);
        accountPasswordService.requestRecovery(request.email(), null);
    }

    /**
     * Checks whether a setup/recovery link is still usable, before the form is
     * shown. Answers 200 with {@code valid=false} for an expired or unknown
     * token rather than an error, so the page can render the right state.
     */
    @GetMapping("/password-token")
    @Operation(summary = "Validate a setup or recovery token",
            description = "Returns whether the token is still usable. Rate limited per client address.")
    public TokenStatusResponse passwordToken(@RequestParam(required = false) String token,
                                             HttpServletRequest httpRequest) {
        String key = rateLimitKey(httpRequest);
        if (recoveryRateLimiter.isBlocked(key)) {
            accountPasswordService.raiseTooManyRequests(recoveryRateLimiter.retryAfterSeconds(key));
        }
        recoveryRateLimiter.recordAttempt(key);
        if (accountTokenService.isUsable(token, AccountTokenType.ACCOUNT_SETUP)) {
            return TokenStatusResponse.valid(AccountTokenType.ACCOUNT_SETUP.name());
        }
        if (accountTokenService.isUsable(token, AccountTokenType.PASSWORD_RECOVERY)) {
            return TokenStatusResponse.valid(AccountTokenType.PASSWORD_RECOVERY.name());
        }
        return TokenStatusResponse.invalid();
    }

    /**
     * Completes the account-setup or password-recovery flow.
     *
     * <p>Rate limited per client address; a rejected token is a 400 with a
     * generic message (the same whatever the reason).
     */
    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Set a password using a setup or recovery token",
            description = "Single-use token. 400 with a generic message when the token is invalid, "
                    + "expired or already used. Rate limited per client address.")
    public void resetPassword(@Valid @RequestBody ResetPasswordRequest request,
                              HttpServletRequest httpRequest) {
        String key = rateLimitKey(httpRequest);
        if (recoveryRateLimiter.isBlocked(key)) {
            accountPasswordService.raiseTooManyRequests(recoveryRateLimiter.retryAfterSeconds(key));
        }
        recoveryRateLimiter.recordAttempt(key);

        AccountTokenType type = accountTokenService.isUsable(request.token(), AccountTokenType.ACCOUNT_SETUP)
                ? AccountTokenType.ACCOUNT_SETUP
                : AccountTokenType.PASSWORD_RECOVERY;
        accountPasswordService.completeWithToken(request.token(), type,
                request.newPassword(), request.confirmPassword());
    }

    /**
     * Authenticated password change. The current password is required; the
     * session stays valid (this session was just re-authenticated) while every
     * other session for the employee is invalidated by the credentials version.
     */
    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Change the signed-in employee's password",
            description = "Requires the current password. Other sessions are signed out. "
                    + "Registered passkeys are not affected.")
    public void changePassword(@Valid @RequestBody ChangePasswordRequest request,
                               Authentication authentication) {
        AuthenticatedEmployee principal = AuthenticatedEmployee.from(authentication);
        accountPasswordService.changePassword(principal.employeeId(),
                request.currentPassword(), request.newPassword(), request.confirmPassword());
        Employee employee = employeeRepository.findById(principal.employeeId()).orElse(null);
        if (employee != null) {
            accountPasswordService.notifyPasswordChanged(employee);
        }
    }

    private String rateLimitKey(HttpServletRequest request) {
        var address = clientIpResolver.resolve(request);
        return address != null ? address.toString() : "unknown";
    }
}
