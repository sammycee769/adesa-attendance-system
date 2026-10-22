package com.adesa.attendance.device;

import com.adesa.attendance.device.dto.DeviceStatusResponse;
import com.adesa.attendance.security.AuthenticatedEmployee;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Employee-facing device status. The employee is always taken from the session. */
@RestController
@RequestMapping("/api/v1/devices")
@Tag(name = "Devices", description = "Registered device status for the signed-in employee")
public class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @GetMapping("/me")
    @Operation(summary = "My device status",
            description = "NONE, PENDING_APPROVAL, APPROVED, REVOKED or REPLACED. Never exposes credential data.")
    public DeviceStatusResponse me(Authentication authentication) {
        return deviceService.getStatus(AuthenticatedEmployee.from(authentication).employeeId());
    }
}
