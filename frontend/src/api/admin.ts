import { apiDownload, apiRequest, buildQuery } from './client';
import type {
  AdminAttendanceRecord,
  AdminDevice,
  AdminDeviceStatus,
  AttendanceCorrection,
  AttendanceOverviewEntry,
  AuditLogEntry,
  Employee,
  EmployeeRole,
  PageResponse,
} from './types';

/**
 * The administrator and HR interfaces.
 *
 * The backend enforces every permission: an HR officer who calls a
 * device-approval endpoint is refused, whatever this frontend shows.
 */

/* ------------------------------------------------------------------ devices */

/** The registered devices, filtered by status (defaults to pending). */
export async function fetchDevices(status: AdminDeviceStatus = 'PENDING_APPROVAL'): Promise<AdminDevice[]> {
  return apiRequest<AdminDevice[]>(`/api/v1/admin/devices${buildQuery({ status })}`);
}

/** Approves a pending device. */
export async function approveDevice(deviceId: string): Promise<AdminDevice> {
  return apiRequest<AdminDevice>(`/api/v1/admin/devices/${deviceId}/approve`, { method: 'POST' });
}

/** Revokes an approved device. The employee loses their sessions immediately. */
export async function revokeDevice(deviceId: string, reason?: string): Promise<AdminDevice> {
  return apiRequest<AdminDevice>(`/api/v1/admin/devices/${deviceId}/revoke`, {
    method: 'POST',
    body: reason ? { reason } : undefined,
  });
}

/** Marks a device as replaced, so the employee can register a new one. */
export async function replaceDevice(deviceId: string, reason?: string): Promise<AdminDevice> {
  return apiRequest<AdminDevice>(`/api/v1/admin/devices/${deviceId}/replace`, {
    method: 'POST',
    body: reason ? { reason } : undefined,
  });
}

/* ---------------------------------------------------------------- employees */

export interface EmployeeQuery {
  role?: EmployeeRole;
  active?: boolean;
  search?: string;
}

/** The employee roster. Readable by HR, changeable by ADMIN only. */
export async function fetchEmployees(query: EmployeeQuery = {}): Promise<Employee[]> {
  return apiRequest<Employee[]>(`/api/v1/admin/employees${buildQuery({ ...query })}`);
}

/** The body of a new employee account. */
export interface CreateEmployeeBody {
  employeeNumber: string;
  fullName: string;
  email: string;
  /** Optional: when omitted the backend e-mails a one-time setup link. */
  password?: string;
  role: EmployeeRole;
}

/** Creates an employee account (ADMIN only). */
export async function createEmployee(body: CreateEmployeeBody): Promise<Employee> {
  return apiRequest<Employee>('/api/v1/admin/employees', { method: 'POST', body });
}

/** A partial update of an employee account (ADMIN only). */
export interface UpdateEmployeeBody {
  fullName?: string;
  email?: string;
  role?: EmployeeRole;
  isActive?: boolean;
  password?: string;
}

/** Updates an employee account (ADMIN only). */
export async function updateEmployee(employeeId: string, body: UpdateEmployeeBody): Promise<Employee> {
  return apiRequest<Employee>(`/api/v1/admin/employees/${employeeId}`, { method: 'PATCH', body });
}

/**
 * Reactivates a deactivated account (ADMIN only).
 *
 * This is the only path that may restore an administrator account: the normal
 * PATCH deliberately refuses to deactivate administrators, and this dedicated
 * action lets an authorized administrator bring an inactive administrator back.
 */
export async function reactivateEmployee(employeeId: string): Promise<Employee> {
  return apiRequest<Employee>(`/api/v1/admin/employees/${employeeId}/reactivate`, { method: 'POST' });
}

/** Re-sends the account-setup link for an account that still needs a password (ADMIN only). */
export async function resendEmployeeSetup(employeeId: string): Promise<Employee> {
  return apiRequest<Employee>(`/api/v1/admin/employees/${employeeId}/resend-setup`, { method: 'POST' });
}

/* ---------------------------------------------------------------- attendance */

export interface AttendanceQuery {
  employeeId?: string;
  search?: string;
  from?: string;
  to?: string;
  status?: 'SIGNED_IN' | 'SIGNED_OUT';
  page?: number;
  size?: number;
}

/** A page of attendance records for the report. */
export async function fetchAttendance(
  query: AttendanceQuery = {},
): Promise<PageResponse<AdminAttendanceRecord>> {
  return apiRequest<PageResponse<AdminAttendanceRecord>>(
    `/api/v1/admin/attendance${buildQuery({ ...query })}`,
  );
}

/** Every active employee with their state for one workday. */
export async function fetchAttendanceOverview(
  date?: string,
): Promise<AttendanceOverviewEntry[]> {
  return apiRequest<AttendanceOverviewEntry[]>(
    `/api/v1/admin/attendance/overview${buildQuery({ date })}`,
  );
}

/** The attendance report as a PDF file. */
export async function exportAttendancePdf(query: AttendanceQuery = {}): Promise<Blob> {
  return apiDownload('/api/v1/admin/attendance/export', { params: { ...query } });
}

/** Corrects an attendance record. The reason is mandatory. */
export async function correctAttendance(
  recordId: string,
  correction: AttendanceCorrection,
): Promise<AdminAttendanceRecord> {
  return apiRequest<AdminAttendanceRecord>(`/api/v1/admin/attendance/${recordId}`, {
    method: 'PATCH',
    body: correction,
  });
}

/* ---------------------------------------------------------------- audit log */

export interface AuditQuery {
  action?: string;
  targetType?: string;
  actorId?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

/**
 * The audit trail.
 *
 * ADMIN sees every entry; HR sees the attendance and device entries only. The
 * restriction is applied by the backend, not here.
 */
export async function fetchAuditLogs(query: AuditQuery = {}): Promise<PageResponse<AuditLogEntry>> {
  return apiRequest<PageResponse<AuditLogEntry>>(`/api/v1/admin/audit-logs${buildQuery({ ...query })}`);
}
