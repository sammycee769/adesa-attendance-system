package com.adesa.attendance.auth;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.adesa.attendance.attendance.network.ClientIpResolver;
import com.adesa.attendance.audit.AuditAction;
import com.adesa.attendance.audit.AuditService;
import com.adesa.attendance.auth.dto.CurrentUserResponse;
import com.adesa.attendance.auth.dto.LoginRequest;
import com.adesa.attendance.common.TooManyRequestsException;
import com.adesa.attendance.common.UnauthorizedException;
import com.adesa.attendance.device.DeviceService;
import com.adesa.attendance.employee.Employee;
import com.adesa.attendance.employee.EmployeeRepository;
import com.adesa.attendance.security.AuthenticatedEmployee;
import com.adesa.attendance.security.SessionAttributes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Password sign-in and sign-out.
 *
 * <p>A successful password login establishes a server-side session and rotates
 * the session id (fixation protection). The session records the employee id,
 * the credentials version and the login time; {@code /auth/me} returns the
 * identity and device state.
 *
 * <p>Login failures are audited in their own transaction and are rate limited
 * per account and client address. Error messages never reveal whether the email
 * exists.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final EmployeeRepository employeeRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final LoginRateLimiter loginRateLimiter;
    private final DeviceService deviceService;
    private final ClientIpResolver clientIpResolver;
    private final HttpSessionSecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    public AuthService(EmployeeRepository employeeRepository,
                       PasswordEncoder passwordEncoder,
                       AuditService auditService,
                       LoginRateLimiter loginRateLimiter,
                       DeviceService deviceService,
                       ClientIpResolver clientIpResolver) {
        this.employeeRepository = employeeRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.loginRateLimiter = loginRateLimiter;
        this.deviceService = deviceService;
        this.clientIpResolver = clientIpResolver;
    }

    public CurrentUserResponse login(LoginRequest request, HttpServletRequest httpRequest,
                                     HttpServletResponse httpResponse) {
        String email = normalizeEmail(request.email());
        // The limiter is keyed by the account *and* the client address. Keying
        // by email alone lets an unauthenticated attacker lock a known employee
        // (including an administrator) out of the application. The resolver
        // honours forwarding headers only from configured trusted proxies, so
        // a spoofed header cannot pick the key.
        var resolvedAddress = clientIpResolver.resolve(httpRequest);
        String sourceAddress = resolvedAddress != null ? resolvedAddress.toString() : "unknown";
        String accountKey = email + "|" + sourceAddress;

        if (loginRateLimiter.isBlocked(accountKey)) {
            long retryAfter = loginRateLimiter.retryAfterSeconds(accountKey);
            // Deliberately not audited per attempt: repeated blocked requests
            // would otherwise be an unauthenticated database-write primitive.
            log.warn("Login rate limit rejected a request for {} from {}", email, sourceAddress);
            throw new TooManyRequestsException(
                    "Too many failed sign-in attempts. Please try again later.", retryAfter);
        }

        Employee employee = employeeRepository.findByEmail(email);
        if (employee == null || !employee.isActive()
                || !passwordEncoder.matches(request.password(), employee.getPasswordHash())) {
            int failures = loginRateLimiter.recordFailure(accountKey);
            if (employee != null) {
                auditService.recordInNewTransaction(null, AuditAction.LOGIN_FAILED, "EMPLOYEE",
                        employee.getId(), Map.of("email", email, "failureCount", failures));
            }
            if (loginRateLimiter.isBlocked(accountKey)) {
                // Record the transition into the blocked state once, rather than
                // one audit row for every subsequent blocked attempt.
                auditService.recordInNewTransaction(null, AuditAction.LOGIN_RATE_LIMITED, "EMPLOYEE",
                        employee != null ? employee.getId() : null, Map.of("email", email));
            }
            throw new UnauthorizedException("Invalid email or password");
        }

        loginRateLimiter.reset(accountKey);
        establishSession(employee, httpRequest, httpResponse);
        auditService.recordInNewTransaction(employee.getId(), AuditAction.LOGIN_SUCCEEDED, "EMPLOYEE",
                employee.getId(), null);
        return currentUser(employee);
    }

    public void logout(HttpServletRequest httpRequest) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UUID actorId = AuthenticatedEmployee.actorId(authentication);

        HttpSession session = httpRequest.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();

        if (actorId != null) {
            auditService.recordInNewTransaction(actorId, AuditAction.LOGOUT, "EMPLOYEE", actorId, null);
        }
    }

    @Transactional(readOnly = true)
    public CurrentUserResponse me(Authentication authentication) {
        AuthenticatedEmployee principal = AuthenticatedEmployee.from(authentication);
        Employee employee = employeeRepository.findById(principal.employeeId())
                .orElseThrow(() -> new UnauthorizedException("Your session is no longer valid"));
        return currentUser(employee);
    }

    private CurrentUserResponse currentUser(Employee employee) {
        return CurrentUserResponse.from(employee, deviceService.getStatus(employee.getId()));
    }

    private void establishSession(Employee employee, HttpServletRequest httpRequest,
                                  HttpServletResponse httpResponse) {
        // Rotate the pre-authentication session id to prevent session fixation.
        if (httpRequest.getSession(false) != null) {
            httpRequest.changeSessionId();
        }
        HttpSession session = httpRequest.getSession(true);
        session.setAttribute(SessionAttributes.EMPLOYEE_ID, employee.getId());
        session.setAttribute(SessionAttributes.CREDENTIALS_VERSION, employee.getCredentialsVersion());
        session.setAttribute(SessionAttributes.LOGIN_TIME, Instant.now());

        AuthenticatedEmployee principal = new AuthenticatedEmployee(
                employee.getId(), employee.getEmail(), employee.getFullName(), employee.getRole());
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + employee.getRole().name())));

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);
        log.info("Employee {} signed in", employee.getId());
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
