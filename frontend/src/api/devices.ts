import { apiRequest } from './client';
import type { DeviceStatus } from './types';

/** The employee's own device registration status. */
export async function fetchMyDevice(): Promise<DeviceStatus> {
  return apiRequest<DeviceStatus>('/api/v1/devices/me');
}
