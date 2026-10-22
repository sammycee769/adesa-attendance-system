package com.adesa.attendance.security;

import java.io.IOException;

import com.adesa.attendance.common.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Writes a consistent {@link ApiError} JSON body from the security filter
 * layer, so unauthenticated/forbidden responses look the same as errors raised
 * from controllers instead of the container's default HTML page.
 */
@Component
public class SecurityErrorWriter {

    private final ObjectMapper objectMapper;

    public SecurityErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ApiError error = ApiError.of(status.value(), status.getReasonPhrase(), message, request.getRequestURI());
        objectMapper.writeValue(response.getWriter(), error);
    }
}
