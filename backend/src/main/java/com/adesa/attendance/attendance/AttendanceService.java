package com.adesa.attendance.attendance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.adesa.attendance.attendance.dto.AttendanceHistoryResponse;
import com.adesa.attendance.attendance.dto.AttendanceRecordResponse;
import com.adesa.attendance.attendance.dto.AttendanceStatusResponse;
import com.adesa.attendance.audit.AuditAction;
import com.adesa.attendance.audit.AuditService;
import com.adesa.attendance.common.BadRequestException;
import com.adesa.attendance.common.ConflictException;
import com.adesa.attendance.common.ForbiddenException;
import com.adesa.attendance.common.NotFoundException;
import com.adesa.attendance.common.UnauthorizedException;
import com.adesa.attendance.device.DeviceProof;
import com.adesa.attendance.device.DeviceProofRepository;
import com.adesa.attendance.device.DeviceProofService;
import com.adesa.attendance.device.DeviceService;
import com.adesa.attendance.device.RegisteredDevice;
import com.adesa.attendance.device.RegisteredDeviceStatus;
import com.adesa.attendance.device.dto.DeviceStatusResponse;
import com.adesa.attendance.employee.Employee;
import com.adesa.attendance.employee.EmployeeRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * The attendance engine: sign-in, sign-out, today's status and the employee's
 * own history.
 *
 * <p>Every mutation enforces the same chain of controls, in this order:
 * <ol>
 *   <li>the office-network policy ({@link AttendanceAccessGuard}),</li>
 *   <li>an active account,</li>
 *   <li>an approved registered device, proven by a fresh, single-use,
 *       session-bound device proof,</li>
 *   <li>the attendance business rules (one record per workday, correct
 *       sign-in/sign-out sequence).</li>
 * </ol>
 *
 * <p>Timestamps are produced by the server only. The employee's identity comes
 * from the session — never from the request body — so an employee can only
 * ever record their own attendance.
 */
@Service
public class AttendanceService {

    private static final Logger log = LoggerFactory.getLogger(AttendanceService.class);

    /** Default history window when the caller does not give one (30 days). */
    private static final int DEFAULT_HISTORY_DAYS = 30;

    private final AttendanceRecordRepository attendanceRepository;
    private final EmployeeRepository employeeRepository;
    private final DeviceService deviceService;
    private final DeviceProofRepository deviceProofRepository;
    private final DeviceProofService deviceProofService;
    private final AttendanceAccessGuard accessGuard;
    private final WorkdayResolver workdayResolver;
    private final AuditService auditService;

    public AttendanceService(AttendanceRecordRepository attendanceRepository,
                             EmployeeRepository employeeRepository,
                             DeviceService deviceService,
                             DeviceProofRepository deviceProofRepository,
                             DeviceProofService deviceProofService,
                             AttendanceAccessGuard accessGuard,
                             WorkdayResolver workdayResolver,
                             AuditService auditService) {
        this.attendanceRepository = attendanceRepository;
        this.employeeRepository = employeeRepository;
        this.deviceService = deviceService;
        this.deviceProofRepository = deviceProofRepository;
        this.deviceProofService = deviceProofService;
        this.accessGuard = accessGuard;
        this.workdayResolver = workdayResolver;
        this.auditService = auditService;
    }

    // ------------------------------------------------------------------
    // Mutations
    // ------------------------------------------------------------------

    /**
     * Records the sign-in of the current workday.
     *
     * @return the created record
     * @throws ForbiddenException when the network, the account or the device
     *                             proof does not satisfy the policy
     * @throws ConflictException  when the employee has already signed in today
     */
    @Transactional
    public AttendanceRecordResponse signIn(HttpServletRequest request, UUID employeeId, UUID deviceProofId) {
        accessGuard.requireApprovedNetwork(employeeId, "SIGN_IN", request);
        requireActiveEmployee(employeeId);
        RegisteredDevice approvedDevice = deviceService.requireApprovedDevice(employeeId);
        consumeDeviceProof(employeeId, approvedDevice, deviceProofId, sessionId(request), "SIGN_IN");

        LocalDate attendanceDate = workdayResolver.today();
        Instant now = workdayResolver.now();

        if (attendanceRepository.findByEmployeeIdAndAttendanceDate(employeeId, attendanceDate).isPresent()) {
            reject(employeeId, "SIGN_IN", "ALREADY_SIGNED_IN");
            throw new ConflictException("You have already signed in for today.");
        }

        AttendanceRecord record = new AttendanceRecord(employeeId, attendanceDate, now);
        try {
            attendanceRepository.saveAndFlush(record);
        } catch (DataIntegrityViolationException ex) {
            // Two concurrent sign-ins for the same workday: the unique
            // constraint decided the winner. The loser is rejected instead of
            // creating a second record.
            log.info("Concurrent sign-in detected for employee {} on {}", employeeId, attendanceDate);
            throw new ConflictException("You have already signed in for today.");
        }

        auditService.record(employeeId, AuditAction.ATTENDANCE_SIGNED_IN, "ATTENDANCE_RECORD", record.getId(),
                Map.of("attendanceDate", attendanceDate.toString(),
                        "signInAt", now.toString()));
        log.info("Employee {} signed in on {}", employeeId, attendanceDate);
        return AttendanceRecordResponse.from(record, workdayResolver.zone());
    }

    /**
     * Closes the current workday with a sign-out.
     *
     * @return the updated record
     * @throws ForbiddenException when the network, the account or the device
     *                             proof does not satisfy the policy
     * @throws ConflictException  when there is no sign-in for today, or the
     *                             workday was already signed out
     */
    @Transactional
    public AttendanceRecordResponse signOut(HttpServletRequest request, UUID employeeId, UUID deviceProofId) {
        accessGuard.requireApprovedNetwork(employeeId, "SIGN_OUT", request);
        requireActiveEmployee(employeeId);
        RegisteredDevice approvedDevice = deviceService.requireApprovedDevice(employeeId);
        consumeDeviceProof(employeeId, approvedDevice, deviceProofId, sessionId(request), "SIGN_OUT");

        LocalDate attendanceDate = workdayResolver.today();
        Instant now = workdayResolver.now();

        // The write lock serialises concurrent sign-outs: the second request
        // waits for the first to commit and then sees the closed workday.
        AttendanceRecord record = attendanceRepository.findForUpdate(employeeId, attendanceDate)
                .orElseThrow(() -> {
                    reject(employeeId, "SIGN_OUT", "NO_SIGN_IN_FOR_TODAY");
                    return new ConflictException("There is no sign-in for today. Sign in first.");
                });

        if (record.getSignOutAt() != null) {
            reject(employeeId, "SIGN_OUT", "ALREADY_SIGNED_OUT");
            throw new ConflictException("You have already signed out for today.");
        }

        record.signOut(now);
        attendanceRepository.saveAndFlush(record);

        auditService.record(employeeId, AuditAction.ATTENDANCE_SIGNED_OUT, "ATTENDANCE_RECORD", record.getId(),
                Map.of("attendanceDate", attendanceDate.toString(),
                        "signOutAt", now.toString()));
        log.info("Employee {} signed out on {}", employeeId, attendanceDate);
        return AttendanceRecordResponse.from(record, workdayResolver.zone());
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    /** The employee's attendance state for the current workday. */
    @Transactional(readOnly = true)
    public AttendanceStatusResponse status(UUID employeeId) {
        requireActiveEmployee(employeeId);

        LocalDate attendanceDate = workdayResolver.today();
        AttendanceRecord record = attendanceRepository
                .findByEmployeeIdAndAttendanceDate(employeeId, attendanceDate)
                .orElse(null);

        AttendanceState state = stateOf(record);
        DeviceStatusResponse device = deviceService.getStatus(employeeId);
        boolean deviceApproved = device.status().equals(RegisteredDeviceStatus.APPROVED.name());

        return new AttendanceStatusResponse(
                attendanceDate,
                workdayResolver.zoneId(),
                state,
                record != null ? record.getSignInAt() : null,
                record != null ? record.getSignOutAt() : null,
                deviceApproved && state == AttendanceState.NOT_SIGNED_IN,
                deviceApproved && state == AttendanceState.SIGNED_IN,
                device.status());
    }

    /** The employee's own attendance history, newest workday first. */
    @Transactional(readOnly = true)
    public AttendanceHistoryResponse history(UUID employeeId, LocalDate from, LocalDate to, int page, int size) {
        LocalDate effectiveTo = to != null ? to : workdayResolver.today();
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusDays(DEFAULT_HISTORY_DAYS - 1L);
        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new BadRequestException("'from' must not be later than 'to'.");
        }

        Page<AttendanceRecord> result = attendanceRepository
                .findByEmployeeIdAndAttendanceDateBetweenOrderByAttendanceDateDesc(
                        employeeId, effectiveFrom, effectiveTo, PageRequest.of(page, size));

        List<AttendanceRecordResponse> records = result.getContent().stream()
                .map(record -> AttendanceRecordResponse.from(record, workdayResolver.zone()))
                .toList();
        return new AttendanceHistoryResponse(records, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private AttendanceState stateOf(AttendanceRecord record) {
        if (record == null) {
            return AttendanceState.NOT_SIGNED_IN;
        }
        return record.getSignOutAt() != null ? AttendanceState.SIGNED_OUT : AttendanceState.SIGNED_IN;
    }

    private Employee requireActiveEmployee(UUID employeeId) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new NotFoundException("Employee not found"));
        if (!employee.isActive()) {
            throw new ForbiddenException("Your account is not active. Attendance cannot be recorded.");
        }
        return employee;
    }

    /**
     * Consumes the device proof: a fresh, unused, session-bound proof of a
     * WebAuthn assertion with the employee's currently approved device.
     *
     * <p>The proof is consumed atomically, so it cannot be replayed. The
     * consumption happens inside the caller's transaction: if the attendance
     * action fails afterwards, the proof is not burned.
     */
    private void consumeDeviceProof(UUID employeeId, RegisteredDevice approvedDevice, UUID deviceProofId,
                                    String sessionId, String action) {
        Instant now = workdayResolver.now();
        DeviceProof proof = deviceProofRepository.findById(deviceProofId).orElse(null);

        boolean usable = proof != null
                && proof.getEmployeeId().equals(employeeId)
                && approvedDevice.getId().equals(proof.getDeviceId())
                && proof.getConsumedAt() == null
                && proof.getExpiresAt().isAfter(now);

        if (!usable || !deviceProofService.consume(deviceProofId, employeeId, sessionId)) {
            log.warn("Attendance {} rejected for employee {}: invalid device proof", action, employeeId);
            reject(employeeId, action, "DEVICE_PROOF_INVALID");
            throw new ForbiddenException(
                    "Device authentication is required. Please sign in with your registered device and try again.");
        }
    }

    private String sessionId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw new UnauthorizedException("Your session is no longer valid. Please sign in again.");
        }
        return session.getId();
    }

    /**
     * Records a rejected attendance action as a security event. The entry is
     * written in its own transaction so it survives the rejected request.
     */
    private void reject(UUID employeeId, String action, String reason) {
        auditService.recordInNewTransaction(employeeId, AuditAction.ATTENDANCE_ACTION_REJECTED,
                "ATTENDANCE_RECORD", null, Map.of("action", action, "reason", reason));
    }
}
