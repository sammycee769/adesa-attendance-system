package com.adesa.attendance.auth;

import com.adesa.attendance.auth.dto.CurrentUserResponse;
import java.util.Map;
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

    /** Returns the raw header token to the exact CORS-allowed frontend origin. */
    @GetMapping("/csrf")
    @Operation(summary = "Obtain a CSRF token and cookie")
    public Map<String, String> csrf(HttpServletRequest request, HttpServletResponse response) {
        // The XOR-masked request attribute is for form rendering, not the SPA
        // header. Load the raw repository token (or generate it on first use).
        CsrfToken token = (CsrfToken) request.getAttribute("adesa.rawCsrfToken");
        response.setHeader("Cache-Control", "no-store");
        return Map.of("token", token.getToken());
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
