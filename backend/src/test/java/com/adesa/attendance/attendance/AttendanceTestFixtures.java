package com.adesa.attendance.attendance;

import java.util.Map;
import java.util.UUID;

import com.adesa.attendance.device.DeviceProofService;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Test fixtures for the attendance engine.
 *
 * <p>Creates the rows an attendance action needs: an employee, an approved
 * registered device and a single-use device proof bound to a session. The
 * WebAuthn ceremony itself is covered by {@code WebAuthnFlowTest}; here the
 * proof is inserted directly so the attendance rules can be tested in
 * isolation.
 */
class AttendanceTestFixtures {

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;

    AttendanceTestFixtures(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    String emailFor(UUID employeeId) {
        return "attendance." + employeeId + "@adesa.test";
    }

    /** Creates an EMPLOYEE account with the given password. */
    UUID createEmployee(boolean active, String password) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO employees (id, employee_number, full_name, email, password_hash, role, is_active) "
                        + "VALUES (?, ?, ?, ?, ?, 'EMPLOYEE', ?)",
                id,
                "ATT-" + id.toString().substring(0, 8),
                "Attendance Employee",
                emailFor(id),
                passwordEncoder.encode(password),
                active);
        return id;
    }

    /** Creates an approved registered device (the trust anchor for attendance). */
    UUID approveDevice(UUID employeeId) {
        UUID deviceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO registered_devices (id, employee_id, credential_id, attested_credential_data, "
                        + "sign_count, status, created_at, updated_at, approved_at) "
                        + "VALUES (?, ?, ?, ?, 0, 'APPROVED', now(), now(), now())",
                deviceId, employeeId, "cred-" + deviceId, new byte[] {1, 2, 3});
        return deviceId;
    }

    /** Creates a device that is not approved (pending, revoked or replaced). */
    UUID createUnapprovedDevice(UUID employeeId, String status) {
        UUID deviceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO registered_devices (id, employee_id, credential_id, attested_credential_data, "
                        + "sign_count, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 0, ?, now(), now())",
                deviceId, employeeId, "cred-" + deviceId, new byte[] {1, 2, 3}, status);
        return deviceId;
    }

    /** Issues a live device proof bound to the given session. */
    UUID issueProof(UUID employeeId, UUID deviceId, String sessionId) {
        UUID proofId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO device_proofs (id, employee_id, session_hash, device_id, created_at, expires_at) "
                        + "VALUES (?, ?, ?, ?, now(), now() + interval '5 minutes')",
                proofId, employeeId, DeviceProofService.hashSessionId(sessionId), deviceId);
        return proofId;
    }

    /** Number of attendance records of an employee. */
    int countRecords(UUID employeeId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM attendance_records WHERE employee_id = ?", Integer.class, employeeId);
        return count != null ? count : 0;
    }

    /** Number of audit entries with the given action for an actor. */
    int countAudit(UUID actorId, String action) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = ? AND actor_id = ?", Integer.class, action, actorId);
        return count != null ? count : 0;
    }

    /** Deactivates an account without touching its credentials version. */
    void deactivate(UUID employeeId) {
        jdbcTemplate.update("UPDATE employees SET is_active = false WHERE id = ?", employeeId);
    }

    static Map<String, Object> proofBody(UUID proofId) {
        return Map.of("deviceProofId", proofId);
    }
}
