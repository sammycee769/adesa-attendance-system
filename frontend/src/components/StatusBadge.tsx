import type { AttendanceState, DeviceStatusName } from '../api/types';

const ATTENDANCE_LABELS: Record<AttendanceState, string> = {
  NOT_SIGNED_IN: 'Not signed in',
  SIGNED_IN: 'Signed in',
  SIGNED_OUT: 'Signed out',
};

const DEVICE_LABELS: Record<DeviceStatusName, string> = {
  NONE: 'No device registered',
  PENDING_APPROVAL: 'Waiting for approval',
  APPROVED: 'Approved',
  REVOKED: 'Revoked',
  REPLACED: 'Replaced',
};

export function AttendanceStateBadge({ state }: { state: AttendanceState }) {
  return (
    <span className={`badge badge-${state.toLowerCase().replace(/_/g, '-')}`}>
      {ATTENDANCE_LABELS[state] ?? state}
    </span>
  );
}

export function DeviceStatusBadge({ status }: { status: DeviceStatusName }) {
  return (
    <span className={`badge badge-device-${status.toLowerCase().replace(/_/g, '-')}`}>
      {DEVICE_LABELS[status] ?? status}
    </span>
  );
}

export function deviceStatusLabel(status: DeviceStatusName): string {
  return DEVICE_LABELS[status] ?? status;
}

export function attendanceStateLabel(state: AttendanceState): string {
  return ATTENDANCE_LABELS[state] ?? state;
}
