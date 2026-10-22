package com.adesa.attendance.security;

import java.io.IOException;
import java.time.Instant;

import com.adesa.attendance.config.AuthProperties;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Enforces an absolute session lifetime.
 *
 * <p>Spring's session timeout is idle-based: an active user is never logged
 * out. This filter additionally invalidates any session older than
 * {@code auth.session-absolute-timeout}, regardless of activity.
 *
 * <p>Not a {@code @Component}: it is instantiated and added to the Spring
 * Security chain only. Declaring a {@code Filter} bean would additionally
 * auto-register it as a plain servlet filter, running it outside the security
 * chain and before authentication.
 */
public class AbsoluteSessionTimeoutFilter extends OncePerRequestFilter {

    private final AuthProperties authProperties;
    private final SecurityErrorWriter errorWriter;

    public AbsoluteSessionTimeoutFilter(AuthProperties authProperties, SecurityErrorWriter errorWriter) {
        this.authProperties = authProperties;
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null) {
            Object loginTime = session.getAttribute(SessionAttributes.LOGIN_TIME);
            if (loginTime instanceof Instant established) {
                Instant deadline = established.plus(authProperties.getSessionAbsoluteTimeout());
                if (Instant.now().isAfter(deadline)) {
                    session.invalidate();
                    SecurityContextHolder.clearContext();
                    errorWriter.write(request, response, HttpStatus.UNAUTHORIZED,
                            "Your session has expired. Please sign in again.");
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }
}
