/**
 * Shapes returned by the Spring Boot API.
 *
 * They mirror the backend DTOs exactly; the backend stays authoritative.
 */

export type AttendanceState = 'NOT_SIGNED_IN' | 'SIGNED_IN' | 'SIGNED_OUT';

export type DeviceStatusName =
  | 'NONE'
  | 'PENDING_APPROVAL'
  | 'APPROVED'
  | 'REVOKED'
  | 'REPLACED';

export type EmployeeRole = 'EMPLOYEE' | 'HR' | 'ADMIN';

export interface DeviceStatus {
  status: DeviceStatusName;
  label: string | null;
  registeredAt: string | null;
  approvedAt: string | null;
  revokedAt: string | null;
  revokedReason: string | null;
}

export interface CurrentUser {
  id: string;
  employeeNumber: string;
  fullName: string;
  email: string;
  role: EmployeeRole;
  mustChangePassword: boolean;
  device: DeviceStatus;
}

export interface AttendanceStatus {
  attendanceDate: string;
  timezone: string;
  state: AttendanceState;
  signInAt: string | null;
  signOutAt: string | null;
  canSignIn: boolean;
  canSignOut: boolean;
  deviceStatus: string;
}

export interface AttendanceRecord {
  id: string;
  attendanceDate: string;
  timezone: string;
  state: AttendanceState;
  signInAt: string | null;
  signOutAt: string | null;
}

export interface AttendanceHistory {
  records: AttendanceRecord[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface FieldViolation {
  field: string;
  message: string;
}

/* ------------------------------------------------------------------ admin API */

/** The status of a device as an administrator sees it. */
export type AdminDeviceStatus = 'PENDING_APPROVAL' | 'APPROVED' | 'REVOKED' | 'REPLACED';

/** A registered device, as an administrator sees it. */
export interface AdminDevice {
  id: string;
  employeeId: string;
  employeeNumber: string;
  employeeName: string;
  label: string | null;
  status: AdminDeviceStatus;
  createdAt: string;
  approvedAt: string | null;
  revokedAt: string | null;
  revokedReason: string | null;
}

/** An employee account, as an administrator sees it. */
export interface Employee {
  id: string;
  employeeNumber: string;
  fullName: string;
  email: string;
  role: EmployeeRole;
  isActive: boolean;
  mustChangePassword: boolean;
  createdAt: string;
  updatedAt: string;
}

/** An attendance record as seen by HR or an administrator. */
export interface AdminAttendanceRecord {
  id: string;
  employeeId: string;
  employeeNumber: string;
  employeeName: string;
  employeeRole: string;
  attendanceDate: string;
  timezone: string;
  state: AttendanceState;
  signInAt: string | null;
  signOutAt: string | null;
  corrected: boolean;
  correctionCount: number;
  updatedAt: string;
}

/** One row of the daily attendance overview. */
export interface AttendanceOverviewEntry {
  employeeId: string;
  employeeNumber: string;
  employeeName: string;
  employeeRole: string;
  state: AttendanceState;
  signInAt: string | null;
  signOutAt: string | null;
  corrected: boolean;
}

/** A page of results. */
export interface PageResponse<T> {
  records: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** An audit entry. */
export interface AuditLogEntry {
  id: string;
  actorId: string | null;
  actorEmployeeNumber: string | null;
  actorName: string | null;
  action: string;
  targetType: string | null;
  targetId: string | null;
  details: Record<string, unknown> | null;
  createdAt: string;
}

/** A manual correction of an attendance record. */
export interface AttendanceCorrection {
  signInAt: string;
  signOutAt: string | null;
  reason: string;
}

export interface ApiErrorBody {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  path: string;
  fieldErrors: FieldViolation[];
}

/* --------------------------------------------------- account security API */

/** The result of validating a setup/recovery link. */
export interface TokenStatus {
  valid: boolean;
  type: 'ACCOUNT_SETUP' | 'PASSWORD_RECOVERY' | null;
  reason: string | null;
}

/** Registration options as returned by the backend (base64url encoded). */
export interface RegistrationOptions {
  challengeId: string;
  publicKey: {
    rp: { id: string; name: string };
    user: { id: string; name: string; displayName: string };
    challenge: string;
    pubKeyCredParams: { type: string; alg: number }[];
    timeout: number;
    attestation: string;
    authenticatorSelection: { residentKey: string; userVerification: string };
    excludeCredentials: { type: string; id: string; transports: string[] | null }[];
  };
}

/** Authentication options as returned by the backend (base64url encoded). */
export interface AuthenticationOptions {
  challengeId: string;
  publicKey: {
    challenge: string;
    timeout: number;
    rpId: string;
    userVerification: string;
    allowCredentials: { type: string; id: string; transports: string[] | null }[];
  };
}

export interface DeviceProof {
  deviceProofId: string;
  expiresAt: string;
}
