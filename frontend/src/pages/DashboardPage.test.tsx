import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '../api/client';
import type { AttendanceHistory, AttendanceStatus, DeviceProof } from '../api/types';
import { AuthProvider } from '../auth/AuthProvider';
import { DashboardPage } from './DashboardPage';

const mocks = vi.hoisted(() => ({
  currentUser: vi.fn(),
  fetchToday: vi.fn(),
  fetchHistory: vi.fn(),
  signIn: vi.fn(),
  signOut: vi.fn(),
  authenticateWithDevice: vi.fn(),
}));

vi.mock('../api/auth', () => ({
  currentUser: mocks.currentUser,
  login: vi.fn(),
  logout: vi.fn(),
}));

vi.mock('../api/attendance', () => ({
  fetchToday: mocks.fetchToday,
  fetchHistory: mocks.fetchHistory,
  signIn: mocks.signIn,
  signOut: mocks.signOut,
}));

vi.mock('../api/webauthn', () => ({
  authenticateWithDevice: mocks.authenticateWithDevice,
}));

const EMPLOYEE = {
  id: 'e1',
  employeeNumber: 'AD-0001',
  fullName: 'Ada Okafor',
  email: 'ada.okafor@adesa.example',
  mustChangePassword: false,
  role: 'EMPLOYEE' as const,
  device: {
    status: 'APPROVED' as const,
    label: 'Office phone',
    registeredAt: '2026-10-01T08:00:00Z',
    approvedAt: '2026-10-01T09:00:00Z',
    revokedAt: null,
    revokedReason: null,
  },
};

function status(overrides: Partial<AttendanceStatus> = {}): AttendanceStatus {
  return {
    attendanceDate: '2026-10-21',
    timezone: 'Africa/Lagos',
    state: 'NOT_SIGNED_IN',
    signInAt: null,
    signOutAt: null,
    canSignIn: true,
    canSignOut: false,
    deviceStatus: 'APPROVED',
    ...overrides,
  };
}

function history(records: AttendanceHistory['records'] = []): AttendanceHistory {
  return { records, page: 0, size: 20, totalElements: records.length, totalPages: 1 };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

async function renderDashboard() {
  const view = render(
    <MemoryRouter initialEntries={['/']}>
      <AuthProvider>
        <DashboardPage />
      </AuthProvider>
    </MemoryRouter>,
  );
  await screen.findByRole('heading', { name: 'Attendance' });
  return view;
}

describe('attendance dashboard', () => {
  beforeEach(() => {
    mocks.currentUser.mockResolvedValue(EMPLOYEE);
    mocks.fetchToday.mockResolvedValue(status());
    mocks.fetchHistory.mockResolvedValue(history());
    mocks.authenticateWithDevice.mockResolvedValue({
      deviceProofId: 'proof-1',
      expiresAt: '2026-10-21T10:05:00Z',
    } as DeviceProof);
    mocks.signIn.mockResolvedValue({
      id: 'r1',
      attendanceDate: '2026-10-21',
      timezone: 'Africa/Lagos',
      state: 'SIGNED_IN',
      signInAt: '2026-10-21T09:15:00Z',
      signOutAt: null,
    });
  });

  it('shows the identity, the date in the attendance timezone and the device status', async () => {
    await renderDashboard();

    const identity = screen.getByRole('region', { name: 'Today at ADESA HQ' });
    expect(await within(identity).findByText('AD-0001')).toBeInTheDocument();
    expect(within(identity).getByText('Ada Okafor')).toBeInTheDocument();
    expect(within(identity).getByText(/2026/)).toBeInTheDocument();

    const device = screen.getByRole('region', { name: 'Device' });
    expect(within(device).getByText('Approved')).toBeInTheDocument();
  });

  it('enables Sign In only when the backend allows it', async () => {
    await renderDashboard();

    expect(screen.getByRole('button', { name: 'Sign In' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Sign Out' })).toBeDisabled();
  });

  it('enables Sign Out only when a valid sign-in exists', async () => {
    mocks.fetchToday.mockResolvedValue(
      status({ state: 'SIGNED_IN', signInAt: '2026-10-21T09:15:00Z', canSignIn: false, canSignOut: true }),
    );

    await renderDashboard();

    expect(screen.getByRole('button', { name: 'Sign In' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Sign Out' })).toBeEnabled();
  });

  it('disables both actions when the workday is signed out', async () => {
    mocks.fetchToday.mockResolvedValue(
      status({
        state: 'SIGNED_OUT',
        signInAt: '2026-10-21T09:15:00Z',
        signOutAt: '2026-10-21T17:02:00Z',
        canSignIn: false,
        canSignOut: false,
      }),
    );

    await renderDashboard();

    expect(screen.getByRole('button', { name: 'Sign In' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Sign Out' })).toBeDisabled();
  });

  it('checks the device, records the sign-in and refreshes the state', async () => {
    const user = userEvent.setup();
    mocks.fetchToday
      .mockResolvedValueOnce(status())
      .mockResolvedValueOnce(
        status({ state: 'SIGNED_IN', signInAt: '2026-10-21T09:15:00Z', canSignIn: false, canSignOut: true }),
      );

    await renderDashboard();
    await user.click(screen.getByRole('button', { name: 'Sign In' }));

    await waitFor(() => expect(mocks.signIn).toHaveBeenCalledWith('proof-1'));
    expect(mocks.authenticateWithDevice).toHaveBeenCalledTimes(1);
    // The state is reloaded from the server after the action.
    await waitFor(() => expect(mocks.fetchToday).toHaveBeenCalledTimes(2));
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Sign Out' })).toBeEnabled(),
    );
  });

  it('prevents a second action while a request is running', async () => {
    const user = userEvent.setup();
    const proof = deferred<DeviceProof>();
    mocks.authenticateWithDevice.mockReturnValue(proof.promise);

    await renderDashboard();

    const signInButton = screen.getByRole('button', { name: 'Sign In' });
    await user.click(signInButton);

    await waitFor(() => expect(signInButton).toBeDisabled());
    await user.click(signInButton);

    expect(mocks.authenticateWithDevice).toHaveBeenCalledTimes(1);

    proof.resolve({ deviceProofId: 'proof-1', expiresAt: '2026-10-21T10:05:00Z' } as DeviceProof);
    await waitFor(() => expect(mocks.signIn).toHaveBeenCalledTimes(1));
  });

  it('shows the reason when the office-network policy refuses the action', async () => {
    const user = userEvent.setup();
    mocks.signIn.mockRejectedValue(
      new ApiError(403, 'Attendance is only available from the approved ADESA office network.'),
    );

    await renderDashboard();
    await user.click(screen.getByRole('button', { name: 'Sign In' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('approved ADESA office network');
    expect(alert).toHaveTextContent(/office connection/i);
  });

  it('shows a conflict reported by the backend', async () => {
    const user = userEvent.setup();
    mocks.signIn.mockRejectedValue(new ApiError(409, 'You have already signed in today.'));

    await renderDashboard();
    await user.click(screen.getByRole('button', { name: 'Sign In' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('already signed in today');
  });

  it('lists the attendance history', async () => {
    mocks.fetchHistory.mockResolvedValue(
      history([
        {
          id: 'r1',
          attendanceDate: '2026-10-20',
          timezone: 'Africa/Lagos',
          state: 'SIGNED_OUT',
          signInAt: '2026-10-20T08:58:00Z',
          signOutAt: '2026-10-20T17:10:00Z',
        },
      ]),
    );

    await renderDashboard();

    const historyRegion = screen.getByRole('region', { name: 'History' });
    expect(within(historyRegion).getByText('Tuesday, 20 October 2026')).toBeInTheDocument();
    // The badge and the column header share the word; the badge is the status.
    expect(within(historyRegion).getAllByText('Signed out').length).toBeGreaterThan(0);
    expect(within(historyRegion).getByText('09:58')).toBeInTheDocument();
    expect(within(historyRegion).getByText('1 record in total.')).toBeInTheDocument();
  });
});
