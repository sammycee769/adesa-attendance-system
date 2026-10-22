package com.adesa.attendance.auth;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.adesa.attendance.audit.AuditAction;
import com.adesa.attendance.audit.AuditService;
import com.adesa.attendance.auth.dto.AuthenticationOptionsResponse;
import com.adesa.attendance.auth.dto.DeviceAuthenticationResponse;
import com.adesa.attendance.auth.dto.RegistrationOptionsResponse;
import com.adesa.attendance.auth.dto.VerifyAuthenticationRequest;
import com.adesa.attendance.auth.dto.VerifyRegistrationRequest;
import com.adesa.attendance.common.BadRequestException;
import com.adesa.attendance.common.ConflictException;
import com.adesa.attendance.common.NotFoundException;
import com.adesa.attendance.config.AuthProperties;
import com.adesa.attendance.config.WebAuthnProperties;
import com.adesa.attendance.device.DeviceProof;
import com.adesa.attendance.device.DeviceProofService;
import com.adesa.attendance.device.RegisteredDevice;
import com.adesa.attendance.device.RegisteredDeviceRepository;
import com.adesa.attendance.device.RegisteredDeviceStatus;
import com.adesa.attendance.device.dto.DeviceStatusResponse;
import com.adesa.attendance.employee.Employee;
import com.adesa.attendance.employee.EmployeeRepository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.authenticator.AuthenticatorImpl;
import com.webauthn4j.converter.AttestedCredentialDataConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.AuthenticationData;
import com.webauthn4j.data.AuthenticationParameters;
import com.webauthn4j.data.AuthenticatorTransport;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.RegistrationParameters;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.server.ServerProperty;
import com.webauthn4j.util.Base64UrlUtil;
import com.webauthn4j.util.exception.WebAuthnException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * WebAuthn registration and authentication ceremonies.
 *
 * <p>All cryptographic verification is delegated to the webauthn4j library
 * (challenge, origin, RP id, user presence/verification, signature). This class
 * owns the server-side ceremony state: single-use challenges, the device
 * lifecycle and the session-bound device proof.
 */
@Service
public class WebAuthnService {

    private static final int CHALLENGE_BYTES = 32;
    private static final long CEREMONY_TIMEOUT_MILLIS = 120_000L;

    private final WebAuthnManager webAuthnManager;
    private final AttestedCredentialDataConverter attestedCredentialDataConverter;
    private final EmployeeRepository employeeRepository;
    private final RegisteredDeviceRepository deviceRepository;
    private final WebAuthnChallengeRepository challengeRepository;
    private final DeviceProofService deviceProofService;
    private final WebAuthnProperties webAuthnProperties;
    private final AuthProperties authProperties;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    public WebAuthnService(WebAuthnManager webAuthnManager,
                           ObjectConverter webAuthnObjectConverter,
                           EmployeeRepository employeeRepository,
                           RegisteredDeviceRepository deviceRepository,
                           WebAuthnChallengeRepository challengeRepository,
                           DeviceProofService deviceProofService,
                           WebAuthnProperties webAuthnProperties,
                           AuthProperties authProperties,
                           AuditService auditService,
                           ObjectMapper objectMapper) {
        this.webAuthnManager = webAuthnManager;
        this.attestedCredentialDataConverter = new AttestedCredentialDataConverter(webAuthnObjectConverter);
        this.employeeRepository = employeeRepository;
        this.deviceRepository = deviceRepository;
        this.challengeRepository = challengeRepository;
        this.deviceProofService = deviceProofService;
        this.webAuthnProperties = webAuthnProperties;
        this.authProperties = authProperties;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // Registration ceremony
    // ------------------------------------------------------------------

    @Transactional
    public RegistrationOptionsResponse startRegistration(UUID employeeId) {
        Employee employee = requireEmployee(employeeId);

        if (deviceRepository.existsByEmployeeIdAndStatus(employeeId, RegisteredDeviceStatus.APPROVED)) {
            throw new ConflictException(
                    "An approved device is already registered for this account. Ask an administrator to "
                            + "replace it before registering a new one.");
        }
        // A new registration request supersedes any previous unfinished attempt.
        deviceRepository.deleteByEmployeeIdAndStatus(employeeId, RegisteredDeviceStatus.PENDING_APPROVAL);

        WebAuthnChallenge challenge = createChallenge(employeeId, WebAuthnChallengeType.REGISTRATION);

        RegistrationOptionsResponse.PublicKeyOptions options = new RegistrationOptionsResponse.PublicKeyOptions(
                new RegistrationOptionsResponse.RelyingParty(webAuthnProperties.getRpId(), webAuthnProperties.getRpName()),
                new RegistrationOptionsResponse.UserEntity(
                        Base64UrlUtil.encodeToString(userHandle(employee)),
                        employee.getEmail(),
                        employee.getFullName()),
                challenge.getChallenge(),
                List.of(
                        new RegistrationOptionsResponse.PubKeyCredParam("public-key", -7),
                        new RegistrationOptionsResponse.PubKeyCredParam("public-key", -257),
                        new RegistrationOptionsResponse.PubKeyCredParam("public-key", -8)),
                CEREMONY_TIMEOUT_MILLIS,
                "none",
                new RegistrationOptionsResponse.AuthenticatorSelection(
                        "preferred", userVerificationPreference()),
                List.of());
        return new RegistrationOptionsResponse(challenge.getId(), options);
    }

    @Transactional
    public DeviceStatusResponse finishRegistration(UUID employeeId, VerifyRegistrationRequest request) {
        WebAuthnChallenge challenge = consumeChallenge(
                request.challengeId(), employeeId, WebAuthnChallengeType.REGISTRATION);

        RegistrationData registrationData = verifyRegistration(request.credential(), challenge);

        AttestedCredentialData attestedCredentialData =
                registrationData.getAttestationObject().getAuthenticatorData().getAttestedCredentialData();
        if (attestedCredentialData == null) {
            throw new BadRequestException("The registration response did not contain a credential");
        }

        String credentialId = Base64UrlUtil.encodeToString(attestedCredentialData.getCredentialId());
        replaceStaleCredential(credentialId);

        long signCount = registrationData.getAttestationObject().getAuthenticatorData().getSignCount();
        String transports = joinTransports(registrationData.getTransports());

        RegisteredDevice device = new RegisteredDevice(
                employeeId,
                credentialId,
                attestedCredentialDataConverter.convert(attestedCredentialData),
                signCount,
                transports,
                StringUtils.hasText(request.label()) ? request.label().trim() : null,
                RegisteredDeviceStatus.PENDING_APPROVAL);
        deviceRepository.saveAndFlush(device);

        auditService.record(employeeId, AuditAction.DEVICE_REGISTERED, "REGISTERED_DEVICE", device.getId(),
                Map.of("label", device.getLabel() == null ? "" : device.getLabel()));
        return DeviceStatusResponse.from(device);
    }

    // ------------------------------------------------------------------
    // Authentication ceremony
    // ------------------------------------------------------------------

    @Transactional
    public AuthenticationOptionsResponse startAuthentication(UUID employeeId) {
        requireEmployee(employeeId);
        RegisteredDevice device = deviceRepository
                .findFirstByEmployeeIdAndStatus(employeeId, RegisteredDeviceStatus.APPROVED)
                .orElseThrow(() -> new ConflictException(
                        "No approved device is registered for this account. Register a device and wait for "
                                + "administrator approval."));

        WebAuthnChallenge challenge = createChallenge(employeeId, WebAuthnChallengeType.AUTHENTICATION);

        AuthenticationOptionsResponse.PublicKeyOptions options =
                new AuthenticationOptionsResponse.PublicKeyOptions(
                        challenge.getChallenge(),
                        CEREMONY_TIMEOUT_MILLIS,
                        webAuthnProperties.getRpId(),
                        userVerificationPreference(),
                        List.of(new AuthenticationOptionsResponse.CredentialDescriptor(
                                "public-key",
                                device.getCredentialId(),
                                splitTransports(device.getTransports()))));
        return new AuthenticationOptionsResponse(challenge.getId(), options);
    }

    @Transactional
    public DeviceAuthenticationResponse finishAuthentication(UUID employeeId, String sessionId,
                                                             VerifyAuthenticationRequest request) {
        WebAuthnChallenge challenge = consumeChallenge(
                request.challengeId(), employeeId, WebAuthnChallengeType.AUTHENTICATION);

        String credentialId = extractCredentialId(request.credential());
        RegisteredDevice device = deviceRepository.findByCredentialId(credentialId)
                .orElseThrow(() -> new BadRequestException("The device credential is not recognised"));
        if (!device.getEmployeeId().equals(employeeId)
                || device.getStatus() != RegisteredDeviceStatus.APPROVED) {
            throw new BadRequestException("The device credential is not approved for this account");
        }

        AuthenticationData authenticationData = verifyAuthentication(request.credential(), challenge, device);

        long newSignCount = authenticationData.getAuthenticatorData().getSignCount();
        device.updateAuthentication(newSignCount, Instant.now());
        deviceRepository.saveAndFlush(device);

        DeviceProof proof = deviceProofService.issue(employeeId, device.getId(), sessionId);

        auditService.record(employeeId, AuditAction.DEVICE_AUTHENTICATED, "REGISTERED_DEVICE", device.getId(), null);
        return new DeviceAuthenticationResponse(proof.getId(), proof.getExpiresAt());
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private Employee requireEmployee(UUID employeeId) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new NotFoundException("Employee not found"));
        if (!employee.isActive()) {
            throw new ConflictException("The account is not active");
        }
        return employee;
    }

    private WebAuthnChallenge createChallenge(UUID employeeId, WebAuthnChallengeType type) {
        byte[] value = new byte[CHALLENGE_BYTES];
        secureRandom.nextBytes(value);
        WebAuthnChallenge challenge = new WebAuthnChallenge(
                employeeId,
                type,
                Base64UrlUtil.encodeToString(value),
                Instant.now().plus(authProperties.getWebauthnChallengeTtl()));
        return challengeRepository.saveAndFlush(challenge);
    }

    /**
     * Loads the challenge value and atomically consumes it. Any failure before
     * consumption (unknown/expired/replayed) is a 400; the challenge is never
     * reusable.
     */
    private WebAuthnChallenge consumeChallenge(UUID challengeId, UUID employeeId, WebAuthnChallengeType type) {
        WebAuthnChallenge challenge = challengeRepository.findByIdAndEmployeeId(challengeId, employeeId)
                .filter(candidate -> candidate.getType() == type)
                .orElseThrow(() -> invalidChallenge());
        if (challengeRepository.consume(challengeId, employeeId, type, Instant.now()) != 1) {
            throw invalidChallenge();
        }
        return challenge;
    }

    private static BadRequestException invalidChallenge() {
        return new BadRequestException("The WebAuthn challenge is invalid, expired or already used");
    }

    private RegistrationData verifyRegistration(JsonNode credential, WebAuthnChallenge challenge) {
        try {
            RegistrationParameters parameters = new RegistrationParameters(
                    serverProperty(challenge), webAuthnProperties.isUserVerificationRequired(), true);
            return webAuthnManager.verifyRegistrationResponseJSON(toJson(credential), parameters);
        } catch (WebAuthnException ex) {
            throw new BadRequestException("The device registration could not be verified");
        }
    }

    private AuthenticationData verifyAuthentication(JsonNode credential, WebAuthnChallenge challenge,
                                                    RegisteredDevice device) {
        try {
            AttestedCredentialData attestedCredentialData =
                    attestedCredentialDataConverter.convert(device.getAttestedCredentialData());
            AuthenticatorImpl authenticator = new AuthenticatorImpl(
                    attestedCredentialData, null, device.getSignCount(), parseTransports(device.getTransports()));
            AuthenticationParameters parameters = new AuthenticationParameters(
                    serverProperty(challenge), authenticator,
                    webAuthnProperties.isUserVerificationRequired(), true);
            return webAuthnManager.verifyAuthenticationResponseJSON(toJson(credential), parameters);
        } catch (WebAuthnException ex) {
            throw new BadRequestException("The device assertion could not be verified");
        }
    }

    private ServerProperty serverProperty(WebAuthnChallenge challenge) {
        Set<Origin> origins = new LinkedHashSet<>();
        for (String origin : webAuthnProperties.getAllowedOrigins()) {
            origins.add(new Origin(origin));
        }
        return new ServerProperty(origins, webAuthnProperties.getRpId(),
                new DefaultChallenge(challenge.getChallenge()));
    }

    private String extractCredentialId(JsonNode credential) {
        JsonNode id = credential.get("id");
        if (id == null || !id.isTextual() || !StringUtils.hasText(id.asText())) {
            throw new BadRequestException("The device assertion did not contain a credential id");
        }
        return id.asText();
    }

    private String toJson(JsonNode credential) {
        try {
            return objectMapper.writeValueAsString(credential);
        } catch (JsonProcessingException ex) {
            throw new BadRequestException("The credential payload is malformed");
        }
    }

    private void replaceStaleCredential(String credentialId) {
        deviceRepository.findByCredentialId(credentialId).ifPresent(existing -> {
            if (existing.getStatus() == RegisteredDeviceStatus.APPROVED
                    || existing.getStatus() == RegisteredDeviceStatus.PENDING_APPROVAL) {
                throw new ConflictException("This credential is already registered");
            }
            // A previously revoked/replaced credential may be registered again.
            // Bulk delete so it commits before the replacement row is inserted.
            deviceRepository.deleteByCredentialId(credentialId);
        });
    }

    private String userVerificationPreference() {
        return webAuthnProperties.isUserVerificationRequired() ? "required" : "preferred";
    }

    private static byte[] userHandle(Employee employee) {
        UUID id = employee.getId();
        byte[] handle = new byte[16];
        long most = id.getMostSignificantBits();
        long least = id.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            handle[i] = (byte) (most >>> (8 * (7 - i)));
            handle[8 + i] = (byte) (least >>> (8 * (7 - i)));
        }
        return handle;
    }

    private static String joinTransports(Set<AuthenticatorTransport> transports) {
        if (transports == null || transports.isEmpty()) {
            return null;
        }
        return String.join(",", transports.stream().map(AuthenticatorTransport::getValue).toList());
    }

    private static Set<AuthenticatorTransport> parseTransports(String transports) {
        Set<AuthenticatorTransport> parsed = new LinkedHashSet<>();
        if (transports == null || transports.isBlank()) {
            return parsed;
        }
        for (String value : transports.split(",")) {
            if (StringUtils.hasText(value)) {
                parsed.add(AuthenticatorTransport.create(value.trim()));
            }
        }
        return parsed;
    }

    private static List<String> splitTransports(String transports) {
        List<String> values = new ArrayList<>();
        if (transports != null && !transports.isBlank()) {
            for (String value : transports.split(",")) {
                if (StringUtils.hasText(value)) {
                    values.add(value.trim());
                }
            }
        }
        return values;
    }
}
