package com.adesa.attendance.accounttoken;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.adesa.attendance.audit.AuditAction;
import com.adesa.attendance.audit.AuditService;
import com.adesa.attendance.common.BadRequestException;
import com.adesa.attendance.common.TooManyRequestsException;
import com.adesa.attendance.config.AccountProperties;
import com.adesa.attendance.employee.Employee;
import com.adesa.attendance.employee.EmployeeRepository;
import com.adesa.attendance.mail.AccountMailComposer;
import com.adesa.attendance.mail.MailDeliveryException;
import com.adesa.attendance.mail.MailService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Password setup, recovery and change.
 *
 * <p>Security properties:
 * <ul>
 *   <li>Tokens are single-use and consumed atomically; only their hash is stored.</li>
 *   <li>Public endpoints answer generically so they never reveal whether an
 *       e-mail address is registered.</li>
 *   <li>Every password change bumps the credentials version, which the existing
 *       session filter uses to invalidate other sessions immediately.</li>
 *   <li>Registered passkeys/devices are deliberately <strong>not</strong>
 *       touched by a password change.</li>
 * </ul>
 */
@Service
public class AccountPasswordService {

    private static final Logger log = LoggerFactory.getLogger(AccountPasswordService.class);

    private final EmployeeRepository employeeRepository;
    private final AccountTokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final AuditService auditService;
    private final MailService mailService;
    private final AccountMailComposer mailComposer;
    private final AccountProperties accountProperties;

    public AccountPasswordService(EmployeeRepository employeeRepository,
                                  AccountTokenService tokenService,
                                  PasswordEncoder passwordEncoder,
                                  PasswordPolicy passwordPolicy,
                                  AuditService auditService,
                                  MailService mailService,
                                  AccountMailComposer mailComposer,
                                  AccountProperties accountProperties) {
        this.employeeRepository = employeeRepository;
        this.tokenService = tokenService;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.auditService = auditService;
        this.mailService = mailService;
        this.mailComposer = mailComposer;
        this.accountProperties = accountProperties;
    }

    /**
     * Issues a setup link for a freshly created account and e-mails it.
     *
     * <p>Called from the employee-creation flow. Delivery failures are logged
     * but do not roll back the account creation; the administrator can resend.
     */
    @Transactional
    public void issueAccountSetup(Employee employee, UUID actorId) {
        employee.setMustChangePassword(true);
        String rawToken = tokenService.issue(employee.getId(), AccountTokenType.ACCOUNT_SETUP);
        long ttlHours = accountProperties.getSetupTokenTtl().toHours();
        AccountMailComposer.ComposedMail mail =
                mailComposer.accountSetup(employee.getFullName(), rawToken, ttlHours);
        deliver(employee.getEmail(), mail,
                Map.of("employeeId", employee.getId().toString(), "type", "ACCOUNT_SETUP"));
        auditService.record(actorId, AuditAction.EMPLOYEE_ACCOUNT_SETUP_ISSUED, "EMPLOYEE",
                employee.getId(), Map.of("deliveryEnabled", mailService.isDeliveryEnabled()));
    }

    /**
     * Administrators can re-send a setup link (e.g. the first one expired).
     *
     * @return true when a link was issued for an eligible account
     */
    @Transactional
    public boolean resendAccountSetup(UUID employeeId, UUID actorId) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new com.adesa.attendance.common.NotFoundException("Employee not found"));
        if (!employee.isActive()) {
            throw new BadRequestException("A deactivated account cannot be set up. Reactivate it first.");
        }
        employee.setMustChangePassword(true);
        employeeRepository.saveAndFlush(employee);
        String rawToken = tokenService.issue(employeeId, AccountTokenType.ACCOUNT_SETUP);
        long ttlHours = accountProperties.getSetupTokenTtl().toHours();
        AccountMailComposer.ComposedMail mail =
                mailComposer.accountSetup(employee.getFullName(), rawToken, ttlHours);
        deliver(employee.getEmail(), mail,
                Map.of("employeeId", employeeId.toString(), "type", "ACCOUNT_SETUP", "resend", true));
        auditService.record(actorId, AuditAction.EMPLOYEE_ACCOUNT_SETUP_ISSUED, "EMPLOYEE",
                employeeId, Map.of("deliveryEnabled", mailService.isDeliveryEnabled(), "resend", true));
        return true;
    }

    /**
     * Handles a "forgot password" request.
     *
     * <p>Always succeeds from the caller's point of view: the response must not
     * reveal whether the address is registered, whether the account is active,
     * or whether the e-mail was delivered. The audit entry is written for every
     * request, but the outcome (sent / not applicable) is not exposed.
     */
    @Transactional
    public void requestRecovery(String rawEmail, UUID actorId) {
        String email = rawEmail == null ? "" : rawEmail.trim().toLowerCase(Locale.ROOT);
        Employee employee = employeeRepository.findByEmail(email);
        boolean eligible = employee != null && employee.isActive();
        if (eligible) {
            String rawToken = tokenService.issue(employee.getId(), AccountTokenType.PASSWORD_RECOVERY);
            long ttlMinutes = accountProperties.getRecoveryTokenTtl().toMinutes();
            AccountMailComposer.ComposedMail mail =
                    mailComposer.passwordRecovery(employee.getFullName(), rawToken, ttlMinutes);
            try {
                mailService.send(employee.getEmail(), mail.subject(), mail.textBody(), mail.htmlBody());
            } catch (MailDeliveryException ex) {
                // Deliberately swallowed: the caller still receives a generic
                // success so a delivery failure cannot be used to probe
                // whether the account exists. The failure is logged.
                log.error("Recovery mail could not be delivered for employee {}", employee.getId());
            }
        }
        // One audit row per request, with no account-existence signal in the
        // response. The target id is only recorded when the account exists, and
        // the endpoint itself is rate limited by the caller.
        auditService.recordInNewTransaction(actorId, AuditAction.PASSWORD_RECOVERY_REQUESTED,
                "EMPLOYEE", employee != null ? employee.getId() : null,
                Map.of("email", email, "eligible", eligible));
    }

    /**
     * Completes either flow: validates the presented token, sets the new
     * password and clears the forced-change state.
     *
     * @throws BadRequestException when the token is unknown, expired, already
     *                             used, of the wrong type, or the owner account
     *                             is not active
     */
    @Transactional
    public void completeWithToken(String rawToken, AccountTokenType type, String newPassword,
                                  String confirmation) {
        if (!newPassword.equals(confirmation)) {
            throw new BadRequestException("The two passwords do not match.");
        }
        passwordPolicy.enforce(newPassword);

        Optional<UUID> employeeId = tokenService.consume(rawToken, type);
        if (employeeId.isEmpty()) {
            auditService.recordInNewTransaction(null, AuditAction.PASSWORD_RESET_REJECTED,
                    "ACCOUNT_TOKEN", null, Map.of("type", type.name(), "reason", "INVALID_OR_EXPIRED"));
            throw new BadRequestException("This link is invalid or has expired. Request a new one.");
        }
        Employee employee = employeeRepository.findById(employeeId.get())
                .orElseThrow(() -> new BadRequestException("This link is invalid or has expired."));
        // Inactive employees must not regain access through recovery/setup.
        if (!employee.isActive()) {
            auditService.recordInNewTransaction(null, AuditAction.PASSWORD_RESET_REJECTED,
                    "EMPLOYEE", employee.getId(), Map.of("reason", "ACCOUNT_INACTIVE"));
            throw new BadRequestException("This link is invalid or has expired. Request a new one.");
        }

        applyNewPassword(employee, newPassword);
        employeeRepository.saveAndFlush(employee);

        AuditAction action = type == AccountTokenType.ACCOUNT_SETUP
                ? AuditAction.EMPLOYEE_PASSWORD_SET_UP
                : AuditAction.PASSWORD_RECOVERY_COMPLETED;
        auditService.record(employee.getId(), action, "EMPLOYEE", employee.getId(), null);
    }

    /**
     * Authenticated password change: the current password must be supplied.
     *
     * @throws BadRequestException when the current password is wrong or the new
     *                             password is weak
     */
    @Transactional
    public void changePassword(UUID employeeId, String currentPassword, String newPassword,
                               String confirmation) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new com.adesa.attendance.common.UnauthorizedException(
                        "Your session is no longer valid"));
        if (!employee.isActive()) {
            throw new com.adesa.attendance.common.UnauthorizedException("Your session is no longer valid");
        }
        if (!passwordEncoder.matches(currentPassword, employee.getPasswordHash())) {
            // Not audited per attempt to avoid an authenticated write primitive;
            // the login/recovery limiters already bound unauthenticated abuse.
            throw new BadRequestException("The current password is incorrect.");
        }
        if (!newPassword.equals(confirmation)) {
            throw new BadRequestException("The two passwords do not match.");
        }
        if (passwordEncoder.matches(newPassword, employee.getPasswordHash())) {
            throw new BadRequestException("The new password must be different from the current password.");
        }
        passwordPolicy.enforce(newPassword);

        applyNewPassword(employee, newPassword);
        employeeRepository.saveAndFlush(employee);
        auditService.record(employeeId, AuditAction.PASSWORD_CHANGED, "EMPLOYEE", employeeId, null);
    }

    /** Sends the "your password was changed" notification, without any secret. */
    @Transactional(readOnly = true)
    public void notifyPasswordChanged(Employee employee) {
        AccountMailComposer.ComposedMail mail = mailComposer.passwordChanged(employee.getFullName());
        try {
            mailService.send(employee.getEmail(), mail.subject(), mail.textBody(), mail.htmlBody());
        } catch (MailDeliveryException ex) {
            log.error("Password-change notification could not be delivered for employee {}", employee.getId());
        }
    }

    public boolean isDeliveryEnabled() {
        return mailService.isDeliveryEnabled();
    }

    private void applyNewPassword(Employee employee, String newPassword) {
        employee.setPasswordHash(passwordEncoder.encode(newPassword));
        employee.setMustChangePassword(false);
        // Invalidate every other session. Devices/passkeys are intentionally
        // left untouched.
        employee.bumpCredentialsVersion();
    }

    private void deliver(String to, AccountMailComposer.ComposedMail mail, Map<String, Object> context) {
        try {
            mailService.send(to, mail.subject(), mail.textBody(), mail.htmlBody());
        } catch (MailDeliveryException ex) {
            // The account exists; the administrator can re-send. Log without the link.
            log.error("Setup mail could not be delivered for {}", context.get("employeeId"));
        }
    }

    /** Throws HTTP 429 for the token-verification endpoint. */
    public void raiseTooManyRequests(long retryAfterSeconds) {
        throw new TooManyRequestsException(
                "Too many attempts. Please try again later.", retryAfterSeconds);
    }
}
