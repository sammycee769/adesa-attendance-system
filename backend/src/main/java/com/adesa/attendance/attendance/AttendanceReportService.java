package com.adesa.attendance.attendance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.adesa.attendance.attendance.dto.AdminAttendanceRecordResponse;
import com.adesa.attendance.attendance.dto.AttendanceCorrectionRequest;
import com.adesa.attendance.attendance.dto.AttendanceOverviewEntry;
import com.adesa.attendance.audit.AuditAction;
import com.adesa.attendance.audit.AuditService;
import com.adesa.attendance.common.BadRequestException;
import com.adesa.attendance.common.NotFoundException;
import com.adesa.attendance.common.PageResponse;
import com.adesa.attendance.employee.Employee;
import com.adesa.attendance.employee.EmployeeRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Attendance reporting for HR and administrators.
 *
 * <p>Reads every employee's records (an employee can only ever read their own
 * through {@code /api/v1/attendance/**}), exports them as CSV and applies
 * authorized corrections. A correction always needs a reason, is written to
 * {@code attendance_corrections} and to the audit log, and happens inside one
 * transaction together with the record change.
 */
@Service
public class AttendanceReportService {

    /** Upper bound for a CSV export, so a wide filter cannot exhaust memory. */
    static final int MAX_EXPORT_ROWS = 10_000;

    private final AttendanceRecordRepository attendanceRecordRepository;
    private final AttendanceCorrectionRepository attendanceCorrectionRepository;
    private final EmployeeRepository employeeRepository;
    private final AuditService auditService;
    private final WorkdayResolver workdayResolver;

    public AttendanceReportService(AttendanceRecordRepository attendanceRecordRepository,
                                   AttendanceCorrectionRepository attendanceCorrectionRepository,
                                   EmployeeRepository employeeRepository,
                                   AuditService auditService,
                                   WorkdayResolver workdayResolver) {
        this.attendanceRecordRepository = attendanceRecordRepository;
        this.attendanceCorrectionRepository = attendanceCorrectionRepository;
        this.employeeRepository = employeeRepository;
        this.auditService = auditService;
        this.workdayResolver = workdayResolver;
    }

    /**
     * A page of attendance records for the report.
     *
     * @param filter  the report filter (employee, free text, date range, state)
     * @param page    zero-based page index
     * @param size    page size
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminAttendanceRecordResponse> list(AttendanceReportFilter filter, int page, int size) {
        Page<AttendanceRecord> records = attendanceRecordRepository.findAll(specification(filter),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "attendanceDate")
                        .and(Sort.by(Sort.Direction.ASC, "employeeId"))));
        List<AdminAttendanceRecordResponse> mapped = map(records.getContent());
        return PageResponse.of(mapped, page, size, records.getTotalElements());
    }

    /**
     * All matching records for a CSV export. Refuses an unbounded export
     * instead of building an unbounded report.
     */
    @Transactional(readOnly = true)
    public List<AdminAttendanceRecordResponse> listForExport(AttendanceReportFilter filter) {
        Page<AttendanceRecord> records = attendanceRecordRepository.findAll(specification(filter),
                PageRequest.of(0, MAX_EXPORT_ROWS + 1,
                        Sort.by(Sort.Direction.DESC, "attendanceDate")
                                .and(Sort.by(Sort.Direction.ASC, "employeeId"))));
        if (records.getTotalElements() > MAX_EXPORT_ROWS) {
            throw new BadRequestException(
                    "The export matches more than " + MAX_EXPORT_ROWS + " records; narrow the date range");
        }
        List<AdminAttendanceRecordResponse> mapped = map(records.getContent());
        return mapped;
    }

    /**
     * The daily overview: every active employee with their state for a workday.
     *
     * <p>No record means {@code NOT_SIGNED_IN}. It never means "absent" — that
     * is an HR reporting decision, not an attendance state.
     */
    @Transactional(readOnly = true)
    public List<AttendanceOverviewEntry> overview(LocalDate date) {
        LocalDate workday = date != null ? date : workdayResolver.today();
        List<Employee> employees = employeeRepository.findByActiveTrueOrderByEmployeeNumberAsc();
        Map<UUID, AttendanceRecord> records = attendanceRecordRepository.findByAttendanceDate(workday).stream()
                .collect(Collectors.toMap(AttendanceRecord::getEmployeeId, Function.identity(),
                        (first, second) -> first));
        Map<UUID, Integer> correctionCounts = correctionCounts(
                records.values().stream().map(AttendanceRecord::getId).toList());

        return employees.stream()
                .map(employee -> {
                    AttendanceRecord record = records.get(employee.getId());
                    if (record == null) {
                        return new AttendanceOverviewEntry(employee.getId(), employee.getEmployeeNumber(),
                                employee.getFullName(), employee.getRole().name(),
                                AttendanceState.NOT_SIGNED_IN, null, null, false);
                    }
                    int corrections = correctionCounts.getOrDefault(record.getId(), 0);
                    return new AttendanceOverviewEntry(employee.getId(), employee.getEmployeeNumber(),
                            employee.getFullName(), employee.getRole().name(),
                            record.getSignOutAt() != null ? AttendanceState.SIGNED_OUT : AttendanceState.SIGNED_IN,
                            record.getSignInAt(), record.getSignOutAt(), corrections > 0);
                })
                .toList();
    }

    /**
     * Applies an authorized manual correction.
     *
     * <p>The record change, the correction history entry and the audit entry
     * are written in one transaction: either all of them commit, or none does.
     */
    @Transactional
    public AdminAttendanceRecordResponse correct(UUID recordId, AttendanceCorrectionRequest request, UUID actorId) {
        AttendanceRecord record = attendanceRecordRepository.findByIdForUpdate(recordId)
                .orElseThrow(() -> new NotFoundException("Attendance record not found"));

        Instant previousSignInAt = record.getSignInAt();
        Instant previousSignOutAt = record.getSignOutAt();

        if (request.signOutAt() != null && request.signOutAt().isBefore(request.signInAt())) {
            throw new BadRequestException("The sign-out time cannot be before the sign-in time");
        }

        String reason = request.reason().trim();
        record.applyCorrection(request.signInAt(), request.signOutAt());
        attendanceRecordRepository.saveAndFlush(record);

        attendanceCorrectionRepository.save(new AttendanceCorrection(
                record.getId(), record.getEmployeeId(), previousSignInAt, previousSignOutAt,
                request.signInAt(), request.signOutAt(), reason, actorId));

        Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("reason", reason);
        details.put("attendanceDate", record.getAttendanceDate().toString());
        // Timestamps are stored as ISO-8601 strings, like every other audit
        // detail, so the trail stays readable and stable.
        details.put("previousSignInAt", previousSignInAt == null ? null : previousSignInAt.toString());
        details.put("previousSignOutAt", previousSignOutAt == null ? null : previousSignOutAt.toString());
        details.put("newSignInAt", request.signInAt().toString());
        details.put("newSignOutAt", request.signOutAt() == null ? null : request.signOutAt().toString());
        auditService.record(actorId, AuditAction.ATTENDANCE_CORRECTED, "ATTENDANCE_RECORD", record.getId(), details);

        return toResponse(record, resolveEmployee(record.getEmployeeId()), correctionCounts(List.of(record.getId()))
                .getOrDefault(record.getId(), 0));
    }

    /** The correction history of one record, newest first. */
    @Transactional(readOnly = true)
    public List<AttendanceCorrection> correctionsOf(UUID recordId) {
        if (!attendanceRecordRepository.existsById(recordId)) {
            throw new NotFoundException("Attendance record not found");
        }
        return attendanceCorrectionRepository.findByAttendanceRecordIdOrderByCreatedAtDesc(recordId);
    }

    private List<AdminAttendanceRecordResponse> map(List<AttendanceRecord> records) {
        Map<UUID, Employee> employees = employeesById(
                records.stream().map(AttendanceRecord::getEmployeeId).distinct().toList());
        Map<UUID, Integer> correctionCounts = correctionCounts(
                records.stream().map(AttendanceRecord::getId).toList());

        return records.stream()
                .map(record -> toResponse(record, employees.get(record.getEmployeeId()),
                        correctionCounts.getOrDefault(record.getId(), 0)))
                .toList();
    }

    private AdminAttendanceRecordResponse toResponse(AttendanceRecord record, Employee employee, int correctionCount) {
        return new AdminAttendanceRecordResponse(
                record.getId(),
                record.getEmployeeId(),
                employee != null ? employee.getEmployeeNumber() : null,
                employee != null ? employee.getFullName() : null,
                employee != null ? employee.getRole().name() : null,
                record.getAttendanceDate(),
                workdayResolver.zoneId(),
                record.getSignOutAt() != null ? AttendanceState.SIGNED_OUT : AttendanceState.SIGNED_IN,
                record.getSignInAt(),
                record.getSignOutAt(),
                correctionCount > 0,
                correctionCount,
                record.getUpdatedAt());
    }

    private Map<UUID, Employee> employeesById(List<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return employeeRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Employee::getId, Function.identity()));
    }

    private Employee resolveEmployee(UUID employeeId) {
        return employeeRepository.findById(employeeId).orElse(null);
    }

    private Map<UUID, Integer> correctionCounts(List<UUID> recordIds) {
        if (recordIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Integer> counts = new java.util.HashMap<>();
        for (Object[] row : attendanceCorrectionRepository.countByRecordIds(recordIds)) {
            counts.put((UUID) row[0], ((Number) row[1]).intValue());
        }
        return counts;
    }

    /**
     * Builds the report filter.
     *
     * <p>A free-text search is resolved to employee ids first: the records only
     * store the employee id, and the employee's name, number and email live on
     * the employee row.
     */
    private Specification<AttendanceRecord> specification(AttendanceReportFilter filter) {
        Specification<AttendanceRecord> specification = Specification.where(null);

        if (filter.employeeId() != null) {
            specification = specification.and(
                    (root, query, cb) -> cb.equal(root.get("employeeId"), filter.employeeId()));
        }
        if (StringUtils.hasText(filter.search())) {
            List<UUID> ids = employeeRepository.findIdsBySearch(searchPattern(filter.search()));
            if (ids.isEmpty()) {
                // No employee matches: the result is empty, not "all records".
                return specification.and((root, query, cb) -> cb.disjunction());
            }
            specification = specification.and((root, query, cb) -> root.get("employeeId").in(ids));
        }
        if (filter.from() != null) {
            specification = specification.and(
                    (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("attendanceDate"), filter.from()));
        }
        if (filter.to() != null) {
            specification = specification.and(
                    (root, query, cb) -> cb.lessThanOrEqualTo(root.get("attendanceDate"), filter.to()));
        }
        if (filter.status() != null) {
            if (filter.status() == AttendanceState.SIGNED_IN) {
                specification = specification.and((root, query, cb) -> cb.isNull(root.get("signOutAt")));
            } else if (filter.status() == AttendanceState.SIGNED_OUT) {
                specification = specification.and((root, query, cb) -> cb.isNotNull(root.get("signOutAt")));
            } else {
                throw new BadRequestException(
                        "Only SIGNED_IN and SIGNED_OUT can be used to filter attendance records");
            }
        }
        if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to())) {
            throw new BadRequestException("The start date must not be after the end date");
        }
        return specification;
    }

    private static String searchPattern(String search) {
        return "%" + search.trim().toLowerCase(java.util.Locale.ROOT) + "%";
    }
}
