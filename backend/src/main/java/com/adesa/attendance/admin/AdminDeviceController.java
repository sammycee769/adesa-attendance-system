package com.adesa.attendance.admin;

import java.util.List;
import java.util.UUID;

import com.adesa.attendance.admin.dto.DeviceActionRequest;
import com.adesa.attendance.device.DeviceService;
import com.adesa.attendance.device.RegisteredDeviceStatus;
import com.adesa.attendance.device.dto.DeviceAdminResponse;
import com.adesa.attendance.security.AuthenticatedEmployee;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Device administration: approval (the trust anchor for attendance), revocation
 * and replacement. ADMIN only, enforced by the filter chain and here.
 *
 * <p>Revocation and replacement invalidate the employee's live sessions
 * immediately by bumping their credentials version.
 */
@RestController
@RequestMapping("/api/v1/admin/devices")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin: Devices", description = "Approve, revoke or replace employee devices (ADMIN only)")
public class AdminDeviceController {

    private final DeviceService deviceService;

    public AdminDeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @GetMapping
    @Operation(summary = "List devices by status",
            description = "Requires ADMIN. Defaults to devices awaiting approval.")
    public List<DeviceAdminResponse> list(
            @RequestParam(defaultValue = "PENDING_APPROVAL") RegisteredDeviceStatus status) {
        return deviceService.list(status);
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve a pending device",
            description = "Requires ADMIN. 404 if unknown, 409 if not pending or the employee already "
                    + "has an approved device.")
    public DeviceAdminResponse approve(@PathVariable UUID id, Authentication authentication) {
        return deviceService.approve(id, AuthenticatedEmployee.actorId(authentication));
    }

    @PostMapping("/{id}/revoke")
    @Operation(summary = "Revoke a device",
            description = "Requires ADMIN. Immediately invalidates the employee's sessions. 409 if already "
                    + "revoked or replaced.")
    public DeviceAdminResponse revoke(@PathVariable UUID id,
                                      @Valid @RequestBody(required = false) DeviceActionRequest request,
                                      Authentication authentication) {
        return deviceService.revoke(id, AuthenticatedEmployee.actorId(authentication),
                request != null ? request.reason() : null);
    }

    @PostMapping("/{id}/replace")
    @Operation(summary = "Replace a device",
            description = "Requires ADMIN. Marks the device REPLACED and invalidates the employee's sessions "
                    + "so they can register a new device.")
    public DeviceAdminResponse replace(@PathVariable UUID id,
                                       @Valid @RequestBody(required = false) DeviceActionRequest request,
                                       Authentication authentication) {
        return deviceService.replace(id, AuthenticatedEmployee.actorId(authentication),
                request != null ? request.reason() : null);
    }
}
