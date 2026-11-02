import { ApiError, apiRequest } from './client';
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
  return recordAttendance('sign-in', deviceProofId);
}

export async function signOut(deviceProofId: string): Promise<AttendanceRecord> {
  return recordAttendance('sign-out', deviceProofId);
}

interface LocationFix {
  latitude: number;
  longitude: number;
  accuracyMetres: number;
  timestampMillis: number;
}

function currentLocation(): Promise<LocationFix> {
  if (!navigator.geolocation) {
    return Promise.reject(new ApiError(403, 'Location is unavailable in this browser. Use your phone with location services enabled.'));
  }
  return new Promise((resolve, reject) => {
    navigator.geolocation.getCurrentPosition(
      position => resolve({
        latitude: position.coords.latitude,
        longitude: position.coords.longitude,
        accuracyMetres: position.coords.accuracy,
        timestampMillis: position.timestamp,
      }),
      error => reject(new ApiError(403, error.code === 1
        ? 'Location permission is needed outside the approved office network. Allow precise location in your browser and try again.'
        : 'Your location could not be obtained. Enable precise location and try again.')),
      { enableHighAccuracy: true, maximumAge: 0, timeout: 20_000 },
    );
  });
}

async function recordAttendance(action: 'sign-in' | 'sign-out', deviceProofId: string): Promise<AttendanceRecord> {
  const path = `/api/v1/attendance/${action}`;
  try {
    return await apiRequest<AttendanceRecord>(path, { method: 'POST', body: { deviceProofId } });
  } catch (error) {
    if (!(error instanceof ApiError) || error.status !== 403 || error.code !== 'LOCATION_REQUIRED') throw error;
  }
  const location = await currentLocation();
  // The first attempt performed no attendance write and did not consume the proof.
  // Retry once; a final denial must not create a location prompt/retry loop.
  return apiRequest<AttendanceRecord>(path, { method: 'POST', body: { deviceProofId, location } });
}
