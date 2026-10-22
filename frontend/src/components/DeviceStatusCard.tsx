import type { DeviceStatus } from '../api/types';
import { formatDateTime } from '../utils/format';
import { DeviceStatusBadge } from './StatusBadge';

/** Summary of the employee's registered device. */
export function DeviceStatusCard({ device }: { device: DeviceStatus | null }) {
  return (
    <section className="card" aria-labelledby="device-heading">
      <h2 id="device-heading">Device</h2>

      {device ? (
        <dl className="definition-list">
          <dt>Registration status</dt>
          <dd>
            <DeviceStatusBadge status={device.status} />
          </dd>
          {device.label ? (
            <>
              <dt>Device name</dt>
              <dd>{device.label}</dd>
            </>
          ) : null}
          {device.registeredAt ? (
            <>
              <dt>Registered on</dt>
              <dd>{formatDateTime(device.registeredAt)}</dd>
            </>
          ) : null}
          {device.approvedAt ? (
            <>
              <dt>Approved on</dt>
              <dd>{formatDateTime(device.approvedAt)}</dd>
            </>
          ) : null}
          {device.revokedAt ? (
            <>
              <dt>Revoked on</dt>
              <dd>{formatDateTime(device.revokedAt)}</dd>
            </>
          ) : null}
          {device.revokedReason ? (
            <>
              <dt>Reason</dt>
              <dd>{device.revokedReason}</dd>
            </>
          ) : null}
        </dl>
      ) : (
        <p className="hint">Loading the device status…</p>
      )}
    </section>
  );
}
