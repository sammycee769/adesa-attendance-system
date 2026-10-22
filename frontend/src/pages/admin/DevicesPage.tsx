import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';

import { approveDevice, fetchDevices, replaceDevice, revokeDevice } from '../../api/admin';
import { ApiError } from '../../api/client';
import type { AdminDevice, AdminDeviceStatus } from '../../api/types';
import { ErrorBanner, SuccessBanner } from '../../components/Banners';
import { DeviceStatusBadge } from '../../components/StatusBadge';
import { describeError } from '../../utils/errors';
import { formatDateTime } from '../../utils/format';

/**
 * Device administration.
 *
 * Approving a device is the trust anchor of the attendance system: only an
 * approved device can record attendance. The screen lists the devices by
 * status and offers the three actions the backend allows — approve, revoke
 * and replace.
 *
 * Revoking and replacing invalidate the employee's sessions immediately, so
 * both ask for a reason: it ends up in the audit trail.
 */

const STATUS_FILTERS: { value: AdminDeviceStatus; label: string }[] = [
  { value: 'PENDING_APPROVAL', label: 'Waiting for approval' },
  { value: 'APPROVED', label: 'Approved' },
  { value: 'REVOKED', label: 'Revoked' },
  { value: 'REPLACED', label: 'Replaced' },
];

type TerminateAction = 'revoke' | 'replace';

export function DevicesPage() {
  const navigate = useNavigate();

  const [status, setStatus] = useState<AdminDeviceStatus>('PENDING_APPROVAL');
  const [devices, setDevices] = useState<AdminDevice[]>([]);
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [terminate, setTerminate] = useState<{ device: AdminDevice; action: TerminateAction } | null>(null);
  const [reason, setReason] = useState('');
  const [error, setError] = useState<{ message: string; hint: string | null } | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  const reload = useCallback(async (filter: AdminDeviceStatus) => {
    const result = await fetchDevices(filter);
    setDevices(result);
  }, []);

  useEffect(() => {
    let active = true;
    setLoading(true);
    (async () => {
      try {
        const result = await fetchDevices(status);
        if (active) {
          setDevices(result);
        }
      } catch (cause) {
        if (active) {
          setError(describeError(cause, 'The devices could not be loaded.'));
        }
      } finally {
        if (active) {
          setLoading(false);
        }
      }
    })();
    return () => {
      active = false;
    };
  }, [status]);

  const handleError = (cause: unknown, fallback: string) => {
    if (cause instanceof ApiError && cause.isUnauthorized) {
      navigate('/login', { replace: true });
      return;
    }
    setError(describeError(cause, fallback));
  };

  const handleApprove = async (device: AdminDevice) => {
    if (busyId) {
      return;
    }
    setBusyId(device.id);
    setError(null);
    setSuccess(null);
    try {
      await approveDevice(device.id);
      setSuccess(`The device of ${device.employeeName} is approved.`);
      await reload(status);
    } catch (cause) {
      handleError(cause, 'The device could not be approved.');
    } finally {
      setBusyId(null);
    }
  };

  const handleTerminate = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!terminate || busyId) {
      return;
    }
    if (reason.trim().length < 5) {
      setError({ message: 'Please give a short reason (at least 5 characters).', hint: null });
      return;
    }
    setBusyId(terminate.device.id);
    setError(null);
    setSuccess(null);
    try {
      if (terminate.action === 'revoke') {
        await revokeDevice(terminate.device.id, reason.trim());
        setSuccess(`The device of ${terminate.device.employeeName} was revoked.`);
      } else {
        await replaceDevice(terminate.device.id, reason.trim());
        setSuccess(
          `The device of ${terminate.device.employeeName} was replaced. The employee can register a new device.`,
        );
      }
      setTerminate(null);
      setReason('');
      await reload(status);
    } catch (cause) {
      handleError(cause, 'The device could not be changed.');
    } finally {
      setBusyId(null);
    }
  };

  const openTerminate = (device: AdminDevice, action: TerminateAction) => {
    setTerminate({ device, action });
    setReason('');
    setError(null);
    setSuccess(null);
  };

  return (
    <div className="dashboard">
      <h1>Devices</h1>
      <p className="subtitle">
        A device has to be approved before it can record attendance. Revoking or replacing a device ends the
        employee&apos;s sessions immediately.
      </p>

      <ErrorBanner message={error?.message ?? null} hint={error?.hint ?? null} />
      <SuccessBanner message={success} />

      <section className="card" aria-labelledby="devices-heading">
        <h2 id="devices-heading">Registered devices</h2>

        <div className="filter-row" role="group" aria-label="Device status">
          {STATUS_FILTERS.map((filter) => (
            <button
              key={filter.value}
              type="button"
              className={`button ${status === filter.value ? 'button-primary' : 'button-secondary'}`}
              aria-pressed={status === filter.value}
              onClick={() => setStatus(filter.value)}
            >
              {filter.label}
            </button>
          ))}
        </div>

        {loading ? (
          <p className="page-status" role="status" aria-live="polite">
            Loading the devices…
          </p>
        ) : devices.length === 0 ? (
          <p className="hint">No device has this status.</p>
        ) : (
          <div className="table-scroll">
            <table>
              <caption className="visually-hidden">Registered devices and their status</caption>
              <thead>
                <tr>
                  <th scope="col">Employee</th>
                  <th scope="col">Device</th>
                  <th scope="col">Registered</th>
                  <th scope="col">Status</th>
                  <th scope="col">Actions</th>
                </tr>
              </thead>
              <tbody>
                {devices.map((device) => (
                  <tr key={device.id}>
                    <td>
                      {device.employeeName}
                      <span className="cell-note"> {device.employeeNumber}</span>
                    </td>
                    <td>{device.label ?? 'Unnamed device'}</td>
                    <td>{formatDateTime(device.createdAt)}</td>
                    <td>
                      <DeviceStatusBadge status={device.status} />
                    </td>
                    <td>
                      <div className="button-row">
                        {device.status === 'PENDING_APPROVAL' ? (
                          <button
                            type="button"
                            className="button button-primary"
                            disabled={busyId !== null}
                            onClick={() => handleApprove(device)}
                          >
                            Approve
                          </button>
                        ) : null}
                        {device.status === 'APPROVED' ? (
                          <>
                            <button
                              type="button"
                              className="button button-secondary"
                              disabled={busyId !== null}
                              onClick={() => openTerminate(device, 'replace')}
                            >
                              Replace
                            </button>
                            <button
                              type="button"
                              className="button button-secondary"
                              disabled={busyId !== null}
                              onClick={() => openTerminate(device, 'revoke')}
                            >
                              Revoke
                            </button>
                          </>
                        ) : null}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {terminate ? (
        <section className="card" aria-labelledby="terminate-heading">
          <h2 id="terminate-heading">
            {terminate.action === 'revoke' ? 'Revoke the device' : 'Replace the device'} of{' '}
            {terminate.device.employeeName}
          </h2>
          <p className="hint">
            {terminate.action === 'revoke'
              ? 'The employee will not be able to record attendance with this device, and their sessions end immediately.'
              : 'The employee will be able to register a new device. Their sessions end immediately.'}
          </p>

          <form onSubmit={handleTerminate} noValidate>
            <div className="form-field">
              <label htmlFor="terminate-reason">Reason</label>
              <input
                id="terminate-reason"
                name="terminate-reason"
                type="text"
                maxLength={255}
                autoFocus
                required
                value={reason}
                onChange={(event) => setReason(event.target.value)}
                disabled={busyId !== null}
              />
              <p className="hint">The reason is stored in the audit trail.</p>
            </div>
            <div className="button-row">
              <button type="submit" className="button button-primary" disabled={busyId !== null}>
                {terminate.action === 'revoke' ? 'Revoke the device' : 'Replace the device'}
              </button>
              <button
                type="button"
                className="button button-secondary"
                disabled={busyId !== null}
                onClick={() => setTerminate(null)}
              >
                Cancel
              </button>
            </div>
          </form>
        </section>
      ) : null}
    </div>
  );
}
