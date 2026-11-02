package com.adesa.attendance.attendance.dto;

/** A browser-reported fix, used only for the explicitly enabled location fallback. */
public record AttendanceLocation(Double latitude, Double longitude, Double accuracyMetres, Long timestampMillis) {
}
