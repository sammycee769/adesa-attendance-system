package com.adesa.attendance.admin;

import java.util.List;
import java.util.UUID;

import com.adesa.attendance.employee.EmployeeRole;
import com.adesa.attendance.employee.EmployeeService;
import com.adesa.attendance.employee.dto.CreateEmployeeRequest;
import com.adesa.attendance.employee.dto.EmployeeResponse;
import com.adesa.attendance.employee.dto.UpdateEmployeeRequest;
import com.adesa.attendance.security.AuthenticatedEmployee;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Employee management endpoints.
 *
 * <p>Reading the roster is allowed for HR (they need it to review attendance);
 * creating and changing accounts is ADMIN only. The acting administrator is
 * derived from the authenticated principal, never from the request.
 */
@RestController
@RequestMapping("/api/v1/admin/employees")
@Tag(name = "Admin: Employees", description = "Employee account management (ADMIN only)")
public class AdminEmployeeController {

    private final EmployeeService employeeService;

    public AdminEmployeeController(EmployeeService employeeService) {
        this.employeeService = employeeService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create an employee account",
            description = "Creates an active employee account. Requires ADMIN. "
                    + "409 if the employee number or email already exists.")
    public EmployeeResponse create(@Valid @RequestBody CreateEmployeeRequest request,
                                   Authentication authentication) {
        return employeeService.create(request, AuthenticatedEmployee.actorId(authentication));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('HR','ADMIN')")
    @Operation(summary = "List employees",
            description = "Requires HR or ADMIN. Optional filters: role, active, search (name/email/number).")
    public List<EmployeeResponse> list(
            @RequestParam(required = false) EmployeeRole role,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String search) {
        return employeeService.list(role, active, search);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('HR','ADMIN')")
    @Operation(summary = "Get one employee", description = "Requires HR or ADMIN. 404 if unknown.")
    public EmployeeResponse get(@PathVariable UUID id) {
        return employeeService.get(id);
    }

    @PatchMapping("/{id}")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update an employee",
            description = "Partial update: name, email, role, active flag, password reset. "
                    + "Requires ADMIN. 404 if unknown, 409 on conflicts. All changes are audited. "
                    + "Deactivating an ADMIN account through this endpoint is refused; use the "
                    + "dedicated reactivation endpoint for inactive administrators.")
    public EmployeeResponse update(@PathVariable UUID id,
                                   @Valid @RequestBody UpdateEmployeeRequest request,
                                   Authentication authentication) {
        return employeeService.update(id, request, AuthenticatedEmployee.actorId(authentication));
    }

    @PostMapping("/{id}/reactivate")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Reactivate a deactivated account",
            description = "Restores the active flag of a previously deactivated account, including an "
                    + "administrator account. Requires ADMIN. 404 if unknown, 409 if the account is "
                    + "already active. Attendance history is never changed and all reactivations are audited.")
    public EmployeeResponse reactivate(@PathVariable UUID id, Authentication authentication) {
        return employeeService.reactivate(id, AuthenticatedEmployee.actorId(authentication));
    }

    @PostMapping("/{id}/resend-setup")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Re-send the account-setup link",
            description = "Issues a fresh single-use setup link for an account that still has to choose its "
                    + "password (for example when the first e-mail expired). Requires ADMIN. "
                    + "404 if unknown, 400 if the account is deactivated.")
    public EmployeeResponse resendSetup(@PathVariable UUID id, Authentication authentication) {
        return employeeService.resendSetup(id, AuthenticatedEmployee.actorId(authentication));
    }
}
