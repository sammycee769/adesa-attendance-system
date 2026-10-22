import { apiRequest } from './client';
import type { AttendanceHistory, AttendanceRecord, AttendanceStatus } from './types';

/**
 * The employee's own attendance.
 *
 * The endpoints take no employee id: the backend always uses the session
 * identity, and the frontend never sends one.
 */
export async function fetchToday(): Promise<AttendanceStatus> {
  return apiRequest<AttendanceStatus>('/api/v1/attendance/me/today');
}

export async function fetchHistory(page = 0, size = 20): Promise<AttendanceHistory> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  return apiRequest<AttendanceHistory>(`/api/v1/attendance/me/history?${query.toString()}`);
}

export async function signIn(deviceProofId: string): Promise<AttendanceRecord> {
  return apiRequest<AttendanceRecord>('/api/v1/attendance/sign-in', {
    method: 'POST',
    body: { deviceProofId },
  });
}

export async function signOut(deviceProofId: string): Promise<AttendanceRecord> {
  return apiRequest<AttendanceRecord>('/api/v1/attendance/sign-out', {
    method: 'POST',
    body: { deviceProofId },
  });
}
