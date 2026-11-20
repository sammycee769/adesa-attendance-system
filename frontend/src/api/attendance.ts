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

const freshPositionOptions: PositionOptions = {
  enableHighAccuracy: true,
  maximumAge: 0,
  timeout: locationTimeoutMillis,
};

function locationError(code: number): ApiError {
  if (code === 1) {
    return new ApiError(403, 'Location permission was denied. Allow location access for this website and try again.');
  }
  if (code === 3) {
    return new ApiError(403, 'The location request timed out. Move to a spot with better reception and try again.');
  }
  return new ApiError(403, 'Location is unavailable. Enable Location Services and try again.');
}

function positionFreshness(position: GeolocationPosition, requestedAt: number): 'fresh' | 'stale' | 'invalid' {
  const now = Date.now();
  if (!Number.isSafeInteger(position.timestamp) || position.timestamp > now) return 'invalid';
  return position.timestamp >= requestedAt && position.timestamp >= now - locationTimeoutMillis ? 'fresh' : 'stale';
}

async function waitForFreshPosition(
  geolocation: Geolocation,
  requestedAt: number,
  sawInvalidTimestamp: boolean,
): Promise<GeolocationPosition> {
  let watchId: number | undefined;
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    return await new Promise<GeolocationPosition>((resolve, reject) => {
      const timedOut = () => reject(sawInvalidTimestamp
        ? new ApiError(403, 'The location reading has an invalid timestamp. Check that your phone sets its date and time automatically, then try again.')
        : locationError(3));
      timer = setTimeout(timedOut, locationTimeoutMillis);
      watchId = geolocation.watchPosition(position => {
        const freshness = positionFreshness(position, requestedAt);
        if (freshness === 'fresh') resolve(position);
        if (freshness === 'invalid') sawInvalidTimestamp = true;
      }, error => {
        if (error.code === 3) timedOut();
        else reject(locationError(error.code));
      }, freshPositionOptions);
    });
  } finally {
    clearTimeout(timer);
    if (watchId !== undefined) geolocation.clearWatch(watchId);
  }
}

async function currentLocation(maxAccuracyMetres: number): Promise<LocationFix> {
  const geolocation = navigator.geolocation;
  if (!geolocation) throw locationError(2);

  const requestedAt = Date.now();
  let position = await new Promise<GeolocationPosition>((resolve, reject) => {
    geolocation.getCurrentPosition(resolve, error => reject(locationError(error.code)), freshPositionOptions);
  });
  const freshness = positionFreshness(position, requestedAt);
  if (freshness !== 'fresh') {
    // The location provider can return its most recent fix before it has acquired a new one.
    position = await waitForFreshPosition(geolocation, requestedAt, freshness === 'invalid');
  }
  const { coords: { latitude, longitude, accuracy }, timestamp } = position;
  if (!Number.isFinite(latitude) || Math.abs(latitude) > 90
      || !Number.isFinite(longitude) || Math.abs(longitude) > 180) {
    throw locationError(2);
  }
  if (!Number.isFinite(accuracy) || accuracy <= 0 || accuracy > maxAccuracyMetres) {
    throw new ApiError(403, `Your location reading is too imprecise for attendance. ${preciseLocationHelp} Move to a spot with better reception and try again.`);
  }
  return { latitude, longitude, accuracyMetres: accuracy, timestampMillis: timestamp };
}

async function recordAttendance(action: 'sign-in' | 'sign-out', deviceProofId: string): Promise<AttendanceRecord> {
  const { maxAccuracyMetres } = await apiRequest<{ maxAccuracyMetres: number }>('/api/v1/attendance/location-policy');
  await ensureCsrfToken();
  const location = await currentLocation(maxAccuracyMetres);
  return apiRequest<AttendanceRecord>(`/api/v1/attendance/${action}`, { method: 'POST', body: { deviceProofId, location } });
}
