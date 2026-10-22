package com.adesa.attendance.common;

import java.util.List;

import org.springframework.data.domain.Page;

/**
 * A page of results. Used by the reporting endpoints; the shape matches the
 * employee's own attendance history so the frontend has one pagination model.
 */
public record PageResponse<T>(
        List<T> records,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    public static <T> PageResponse<T> of(List<T> records, int page, int size, long totalElements) {
        int totalPages = size <= 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        return new PageResponse<>(records, page, size, totalElements, totalPages);
    }
}
