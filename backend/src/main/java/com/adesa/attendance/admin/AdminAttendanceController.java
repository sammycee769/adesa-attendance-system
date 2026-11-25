package com.adesa.attendance.admin;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.adesa.attendance.attendance.AttendanceReportFilter;
import com.adesa.attendance.attendance.AttendanceReportService;
import com.adesa.attendance.attendance.AttendanceState;
import com.adesa.attendance.attendance.AttendanceRosterImport;
import com.adesa.attendance.attendance.DailyAttendanceReport;
import com.adesa.attendance.attendance.DailyAttendanceService;
import com.adesa.attendance.attendance.dto.AdminAttendanceRecordResponse;
import com.adesa.attendance.attendance.dto.AttendanceCorrectionRequest;
import com.adesa.attendance.attendance.dto.AttendanceOverviewEntry;
import com.adesa.attendance.common.PageResponse;
import com.adesa.attendance.security.AuthenticatedEmployee;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Attendance reporting for HR and administrators.
 *
 * <p>Every method is authorized for HR and ADMIN. The acting person is always
 * taken from the authenticated principal, never from the request.
 */
@RestController
@RequestMapping("/api/v1/admin/attendance")
@Validated
@Tag(name = "Admin: Attendance", description = "Attendance reporting, export and corrections (HR and ADMIN)")
public class AdminAttendanceController {

    private final AttendanceReportService attendanceReportService;

    private final DailyAttendanceService dailyAttendance;

    private final AttendanceRosterImport rosters;

    public AdminAttendanceController(AttendanceReportService attendanceReportService,
                                     DailyAttendanceService dailyAttendance, AttendanceRosterImport rosters) {
        this.attendanceReportService = attendanceReportService;
        this.dailyAttendance = dailyAttendance;
        this.rosters = rosters;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('HR','ADMIN')")
    @Operation(summary = "List attendance records",
            description = "Requires HR or ADMIN. Optional filters: employeeId, search (name/number/email), "
                    + "from, to (workdays, inclusive), status (SIGNED_IN or SIGNED_OUT). Newest workday first.")
    public PageResponse<AdminAttendanceRecordResponse> list(
            @RequestParam(required = false) UUID employeeId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) AttendanceState status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        AttendanceReportFilter filter = new AttendanceReportFilter(employeeId, search, from, to, status);
        return attendanceReportService.list(filter, page, size);
    }

    @GetMapping("/overview")
    @PreAuthorize("hasAnyRole('HR','ADMIN')")
    @Operation(summary = "Daily attendance overview",
            description = "Requires HR or ADMIN. Every active employee with their state for the workday "
                    + "(defaults to the current workday in the attendance timezone). "
                    + "NOT_SIGNED_IN means no record yet - it never means absent.")
    public List<AttendanceOverviewEntry> overview(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return attendanceReportService.overview(date);
    }

    @GetMapping("/days")
    @PreAuthorize("hasAnyRole('HR','ADMIN')")
    @Operation(summary = "Attendance grouped by working day")
    public DailyAttendanceReport days(DailyAttendanceReport.Filter filter,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "7") @Min(1) @Max(31) int size) {
        return dailyAttendance.read(filter, page, size, false);
    }

    @GetMapping("/export")
    @PreAuthorize("hasAnyRole('HR','ADMIN')")
    @Operation(summary = "Export daily attendance as a branded PDF")
    public ResponseEntity<byte[]> export(DailyAttendanceReport.Filter filter) {
        var report = dailyAttendance.read(filter, 0, 0, true);
        byte[] pdf = AttendancePdfWriter.write(report, filter);
        String filename = "adesa-attendance-" + report.from() + "-to-" + report.to() + ".pdf";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(pdf.length)
                .body(pdf);
    }

    @GetMapping("/export.csv")
    @PreAuthorize("hasAnyRole('HR','ADMIN')")
    @Operation(summary = "Export daily attendance as CSV")
    public ResponseEntity<byte[]> exportCsv(DailyAttendanceReport.Filter filter) {
        byte[] csv = AttendanceCsvWriter.write(dailyAttendance.read(filter, 0, 0, true));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"adesa-attendance.csv\"")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .contentLength(csv.length)
                .body(csv);
    }

    @GetMapping("/roster/template")
    @PreAuthorize("hasAnyRole('HR','ADMIN')")
    public ResponseEntity<byte[]> rosterTemplate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"attendance-roster-" + date + ".tsv\"")
                .contentType(MediaType.parseMediaType("text/tab-separated-values;charset=UTF-8"))
                .body(rosters.template(date));
    }

    public record RosterUpload(String rows, String sourceReference, boolean verified) {}

    @org.springframework.web.bind.annotation.PostMapping("/roster")
    @PreAuthorize("hasAnyRole('HR','ADMIN')")
    public java.util.Map<String,Integer> importRoster(@RequestBody RosterUpload upload, Authentication authentication) {
        if (!upload.verified()) throw new com.adesa.attendance.common.BadRequestException("Confirm the roster against approved schedules");
        int imported = rosters.load(upload.rows(),upload.sourceReference(),AuthenticatedEmployee.actorId(authentication));
        return java.util.Map.of("imported",imported);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('HR','ADMIN')")
    @Operation(summary = "Correct an attendance record",
            description = "Requires HR or ADMIN. The reason is mandatory and at least 10 characters. "
                    + "The correction, its history entry and the audit entry are written in one transaction. "
                    + "404 if the record does not exist.")
    public AdminAttendanceRecordResponse correct(@PathVariable UUID id,
                                                  @Valid @RequestBody AttendanceCorrectionRequest request,
                                                  Authentication authentication) {
        return attendanceReportService.correct(id, request, AuthenticatedEmployee.actorId(authentication));
    }
}
