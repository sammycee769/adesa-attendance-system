package com.adesa.attendance.auth;

import com.adesa.attendance.auth.dto.CurrentUserResponse;
import com.adesa.attendance.auth.dto.LoginRequest;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

/**
 * Session authentication: password sign-in, sign-out, current identity and the
 * CSRF token bootstrap for the SPA.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Session sign-in, sign-out and identity")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Establishes the CSRF cookie for the SPA. The cookie is HTML-readable
     * (not HttpOnly) so JavaScript can echo it as the {@code X-XSRF-TOKEN}
     * header; the server never trusts it without a matching session token.
     */
    @GetMapping("/csrf")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Obtain a CSRF token cookie",
            description = "Sets the XSRF-TOKEN cookie and returns 204. Call before any state-changing request.")
    public void csrf(CsrfToken csrfToken) {
        // Touching the CsrfToken argument forces the token to be generated and
        // written to the cookie repository by the CSRF filter.
        csrfToken.getToken();
    }

    @PostMapping("/login")
    @Operation(summary = "Sign in with employee number email and password",
            description = "Creates a server-side session. 401 on bad credentials, 429 when rate limited.")
    public CurrentUserResponse login(@Valid @RequestBody LoginRequest request,
                                     HttpServletRequest httpRequest,
                                     HttpServletResponse httpResponse) {
        return authService.login(request, httpRequest, httpResponse);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Sign out", description = "Invalidates the session and clears the security context.")
    public void logout(HttpServletRequest httpRequest) {
        authService.logout(httpRequest);
    }

    @GetMapping("/me")
    @Operation(summary = "Current identity and device state")
    public CurrentUserResponse me(Authentication authentication) {
        return authService.me(authentication);
    }
}
