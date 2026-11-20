import { ApiError, apiRequest, ensureCsrfToken } from './client';
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

const locationTimeoutMillis = 15_000;

const preciseLocationHelp =
  'On iPhone or iPad, open Settings > Privacy & Security > Location Services > Safari Websites and turn on Precise Location.';

function currentLocation(maxAccuracyMetres: number): Promise<LocationFix> {
  if (!navigator.geolocation) {
    return Promise.reject(new ApiError(403, 'Location is unavailable in this browser. Enable Location Services and try again.'));
  }
  const requestedAt = Date.now();
  return new Promise((resolve, reject) => {
    navigator.geolocation.getCurrentPosition(
      ({ coords: { latitude, longitude, accuracy }, timestamp }) => {
        const now = Date.now();
        if (!Number.isFinite(latitude) || Math.abs(latitude) > 90
            || !Number.isFinite(longitude) || Math.abs(longitude) > 180) {
          reject(new ApiError(403, 'Location is unavailable. Enable Location Services and try again.'));
        } else if (!Number.isSafeInteger(timestamp) || timestamp < requestedAt
            || timestamp < now - locationTimeoutMillis || timestamp > now) {
          reject(new ApiError(403, 'Your location reading is out of date or has an invalid timestamp. Try again for a fresh reading.'));
        } else if (!Number.isFinite(accuracy) || accuracy <= 0 || accuracy > maxAccuracyMetres) {
          reject(new ApiError(403, `Your location reading is too imprecise for attendance. ${preciseLocationHelp} Move to a spot with better reception and try again.`));
        } else {
          resolve({ latitude, longitude, accuracyMetres: accuracy, timestampMillis: timestamp });
        }
      },
      ({ code }) => {
        const message = code === 1
          ? 'Location permission was denied. Allow location access for this website and try again.'
          : code === 3
            ? 'The location request timed out. Move to a spot with better reception and try again.'
            : 'Location is unavailable. Enable Location Services and try again.';
        reject(new ApiError(403, message));
      },
      { enableHighAccuracy: true, maximumAge: 0, timeout: locationTimeoutMillis },
    );
  });
}

async function recordAttendance(action: 'sign-in' | 'sign-out', deviceProofId: string): Promise<AttendanceRecord> {
  const { maxAccuracyMetres } = await apiRequest<{ maxAccuracyMetres: number }>('/api/v1/attendance/location-policy');
  await ensureCsrfToken();
  const location = await currentLocation(maxAccuracyMetres);
  return apiRequest<AttendanceRecord>(`/api/v1/attendance/${action}`, { method: 'POST', body: { deviceProofId, location } });
}
