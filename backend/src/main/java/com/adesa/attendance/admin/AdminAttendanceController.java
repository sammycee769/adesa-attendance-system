package com.adesa.attendance.admin;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.adesa.attendance.attendance.AttendanceReportFilter;
import com.adesa.attendance.attendance.AttendanceReportService;
import com.adesa.attendance.attendance.AttendanceState;
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

    public AdminAttendanceController(AttendanceReportService attendanceReportService) {
        this.attendanceReportService = attendanceReportService;
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

    @GetMapping("/export")
    @PreAuthorize("hasAnyRole('HR','ADMIN')")
    @Operation(summary = "Export attendance records as PDF",
            description = "Requires HR or ADMIN. Accepts the same filters as the list endpoint and returns "
                    + "a PDF report. Large exports are refused instead of being truncated silently.")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) UUID employeeId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) AttendanceState status) {
        AttendanceReportFilter filter = new AttendanceReportFilter(employeeId, search, from, to, status);
        List<AdminAttendanceRecordResponse> records = attendanceReportService.listForExport(filter);
        byte[] pdf = AttendancePdfWriter.write(records, from, to);
        String filename = "adesa-attendance-" + (from != null ? from : "all") + "-to-" + (to != null ? to : "all")
                + ".pdf";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(pdf.length)
                .body(pdf);
    }

    @GetMapping("/export.csv")
    @PreAuthorize("hasAnyRole('HR','ADMIN')")
    @Operation(summary = "Export attendance records as CSV")
    public ResponseEntity<byte[]> exportCsv(
            @RequestParam(required = false) UUID employeeId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) AttendanceState status) {
        var filter = new AttendanceReportFilter(employeeId, search, from, to, status);
        byte[] csv = AttendanceCsvWriter.write(attendanceReportService.listForExport(filter));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"adesa-attendance.csv\"")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .contentLength(csv.length)
                .body(csv);
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
