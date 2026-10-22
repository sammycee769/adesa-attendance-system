package com.adesa.attendance.device;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.adesa.attendance.audit.AuditAction;
import com.adesa.attendance.audit.AuditService;
import com.adesa.attendance.common.ConflictException;
import com.adesa.attendance.common.NotFoundException;
import com.adesa.attendance.device.dto.DeviceAdminResponse;
import com.adesa.attendance.device.dto.DeviceStatusResponse;
import com.adesa.attendance.employee.Employee;
import com.adesa.attendance.employee.EmployeeRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Device lifecycle management: the employee-facing status, plus administrator
 * approval, revocation and replacement.
 *
 * <p>Administrators are the approval authority — a newly registered credential
 * is useless until approved. Revocation and replacement bump the employee's
 * credentials version, which invalidates their live sessions immediately.
 */
@Service
public class DeviceService {

    private final RegisteredDeviceRepository deviceRepository;
    private final EmployeeRepository employeeRepository;
    private final AuditService auditService;

    public DeviceService(RegisteredDeviceRepository deviceRepository, EmployeeRepository employeeRepository,
                         AuditService auditService) {
        this.deviceRepository = deviceRepository;
        this.employeeRepository = employeeRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public DeviceStatusResponse getStatus(UUID employeeId) {
        return mostRelevantDevice(employeeId)
                .map(DeviceStatusResponse::from)
                .orElseGet(DeviceStatusResponse::none);
    }

    @Transactional(readOnly = true)
    public RegisteredDevice requireApprovedDevice(UUID employeeId) {
        return deviceRepository.findFirstByEmployeeIdAndStatus(employeeId, RegisteredDeviceStatus.APPROVED)
                .orElseThrow(() -> new ConflictException(
                        "No approved device is registered for this account. Register a device and wait for "
                                + "administrator approval."));
    }

    @Transactional(readOnly = true)
    public List<DeviceAdminResponse> list(RegisteredDeviceStatus status) {
        return deviceRepository
                .findByStatusOrderByCreatedAtAsc(status)
                .stream()
                .map(this::toAdminResponse)
                .toList();
    }

    @Transactional
    public DeviceAdminResponse approve(UUID deviceId, UUID actorId) {
        RegisteredDevice device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new NotFoundException("Device not found"));
        if (device.getStatus() != RegisteredDeviceStatus.PENDING_APPROVAL) {
            throw new ConflictException("Only a device awaiting approval can be approved");
        }
        deviceRepository.findFirstByEmployeeIdAndStatus(device.getEmployeeId(), RegisteredDeviceStatus.APPROVED)
                .ifPresent(existing -> {
                    throw new ConflictException(
                            "This employee already has an approved device; revoke or replace it first");
                });

        device.approve(actorId, Instant.now());
        deviceRepository.saveAndFlush(device);
        auditService.record(actorId, AuditAction.DEVICE_APPROVED, "REGISTERED_DEVICE", device.getId(),
                Map.of("employeeId", device.getEmployeeId().toString()));
        return toAdminResponse(device);
    }

    @Transactional
    public DeviceAdminResponse revoke(UUID deviceId, UUID actorId, String reason) {
        return terminate(deviceId, actorId, reason, RegisteredDeviceStatus.REVOKED, AuditAction.DEVICE_REVOKED);
    }

    @Transactional
    public DeviceAdminResponse replace(UUID deviceId, UUID actorId, String reason) {
        return terminate(deviceId, actorId, reason, RegisteredDeviceStatus.REPLACED, AuditAction.DEVICE_REPLACED);
    }

    private DeviceAdminResponse terminate(UUID deviceId, UUID actorId, String reason,
                                          RegisteredDeviceStatus terminalStatus, AuditAction auditAction) {
        RegisteredDevice device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new NotFoundException("Device not found"));
        if (device.getStatus() == RegisteredDeviceStatus.REVOKED
                || device.getStatus() == RegisteredDeviceStatus.REPLACED) {
            throw new ConflictException("This device is already revoked or replaced");
        }

        int updated = deviceRepository.revokeIfStatus(
                deviceId,
                device.getEmployeeId(),
                device.getStatus(),
                terminalStatus,
                actorId,
                reason,
                Instant.now());
        if (updated != 1) {
            throw new ConflictException("The device status changed while the request was being processed");
        }

        invalidateEmployeeSessions(device.getEmployeeId());
        auditService.record(actorId, auditAction, "REGISTERED_DEVICE", deviceId,
                Map.of("employeeId", device.getEmployeeId().toString(),
                        "reason", reason == null ? "" : reason));
        return toAdminResponse(deviceRepository.findById(deviceId).orElseThrow());
    }

    private void invalidateEmployeeSessions(UUID employeeId) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new NotFoundException("Employee not found"));
        employee.bumpCredentialsVersion();
        employeeRepository.saveAndFlush(employee);
    }

    /**
     * Most relevant device for display: an approved device wins, otherwise the
     * newest registration.
     */
    private java.util.Optional<RegisteredDevice> mostRelevantDevice(UUID employeeId) {
        List<RegisteredDevice> devices = deviceRepository.findByEmployeeIdOrderByCreatedAtDesc(employeeId);
        return devices.stream()
                .filter(device -> device.getStatus() == RegisteredDeviceStatus.APPROVED)
                .findFirst()
                .or(() -> devices.stream().findFirst());
    }

    private DeviceAdminResponse toAdminResponse(RegisteredDevice device) {
        Employee employee = employeeRepository.findById(device.getEmployeeId()).orElse(null);
        String number = employee != null ? employee.getEmployeeNumber() : null;
        String name = employee != null ? employee.getFullName() : null;
        return DeviceAdminResponse.from(device, number, name);
    }
}
