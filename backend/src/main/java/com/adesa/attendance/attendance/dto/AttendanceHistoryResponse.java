package com.adesa.attendance.attendance.dto;

import java.util.List;

/**
 * A page of the employee's own attendance history.
 */
public record AttendanceHistoryResponse(
        List<AttendanceRecordResponse> records,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
