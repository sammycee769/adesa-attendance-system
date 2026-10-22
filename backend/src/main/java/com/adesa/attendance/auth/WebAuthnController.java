package com.adesa.attendance.auth;

import com.adesa.attendance.auth.dto.AuthenticationOptionsResponse;
import com.adesa.attendance.auth.dto.DeviceAuthenticationResponse;
import com.adesa.attendance.auth.dto.RegistrationOptionsResponse;
import com.adesa.attendance.auth.dto.VerifyAuthenticationRequest;
import com.adesa.attendance.auth.dto.VerifyRegistrationRequest;
import com.adesa.attendance.device.dto.DeviceStatusResponse;
import com.adesa.attendance.security.AuthenticatedEmployee;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * WebAuthn ceremonies for the signed-in employee.
 *
 * <p>Registration creates a device awaiting administrator approval.
 * Authentication proves possession of the approved device and issues a
 * single-use, session-bound proof that attendance actions consume.
 */
@RestController
@RequestMapping("/api/v1/auth/webauthn")
@Tag(name = "WebAuthn", description = "Device registration and authentication ceremonies")
public class WebAuthnController {

    private final WebAuthnService webAuthnService;

    public WebAuthnController(WebAuthnService webAuthnService) {
        this.webAuthnService = webAuthnService;
    }

    @PostMapping("/register/options")
    @Operation(summary = "Start device registration",
            description = "Returns PublicKeyCredentialCreationOptions. 409 if a device is already approved.")
    public RegistrationOptionsResponse registerOptions(Authentication authentication) {
        return webAuthnService.startRegistration(AuthenticatedEmployee.from(authentication).employeeId());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Finish device registration",
            description = "Verifies the attestation. The device is created with PENDING_APPROVAL status.")
    public DeviceStatusResponse register(@Valid @RequestBody VerifyRegistrationRequest request,
                                         Authentication authentication) {
        return webAuthnService.finishRegistration(
                AuthenticatedEmployee.from(authentication).employeeId(), request);
    }

    @PostMapping("/authenticate/options")
    @Operation(summary = "Start device authentication",
            description = "Returns PublicKeyCredentialRequestOptions for the approved device. "
                    + "409 if no device is approved.")
    public AuthenticationOptionsResponse authenticateOptions(Authentication authentication) {
        return webAuthnService.startAuthentication(AuthenticatedEmployee.from(authentication).employeeId());
    }

    @PostMapping("/authenticate")
    @Operation(summary = "Finish device authentication",
            description = "Verifies the assertion and issues a single-use, session-bound device proof.")
    public DeviceAuthenticationResponse authenticate(@Valid @RequestBody VerifyAuthenticationRequest request,
                                                     Authentication authentication,
                                                     HttpServletRequest httpRequest) {
        String sessionId = httpRequest.getSession(false) == null
                ? null
                : httpRequest.getSession(false).getId();
        return webAuthnService.finishAuthentication(
                AuthenticatedEmployee.from(authentication).employeeId(), sessionId, request);
    }
}
