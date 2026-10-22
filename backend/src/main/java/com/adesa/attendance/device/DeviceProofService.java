package com.adesa.attendance.device;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import com.adesa.attendance.config.AuthProperties;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and consumes device proofs.
 *
 * <p>A proof means "the holder of this session successfully presented an
 * approved device credential moments ago". Attendance actions consume a proof
 * exactly once, from the same session. The raw session id is never stored —
 * only its SHA-256 hash.
 */
@Service
public class DeviceProofService {

    private final DeviceProofRepository deviceProofRepository;
    private final AuthProperties authProperties;

    public DeviceProofService(DeviceProofRepository deviceProofRepository, AuthProperties authProperties) {
        this.deviceProofRepository = deviceProofRepository;
        this.authProperties = authProperties;
    }

    @Transactional
    public DeviceProof issue(UUID employeeId, UUID deviceId, String sessionId) {
        DeviceProof proof = new DeviceProof(
                employeeId,
                hashSessionId(sessionId),
                deviceId,
                Instant.now().plus(authProperties.getDeviceProofTtl()));
        return deviceProofRepository.saveAndFlush(proof);
    }

    /**
     * Consumes the proof for the given employee and session.
     *
     * @return true only if this exact call consumed a live, unused proof
     */
    @Transactional
    public boolean consume(UUID proofId, UUID employeeId, String sessionId) {
        return deviceProofRepository.consume(proofId, employeeId, hashSessionId(sessionId), Instant.now()) == 1;
    }

    public static String hashSessionId(String sessionId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(sessionId.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
