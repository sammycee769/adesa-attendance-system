import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '../api/client';
import type { CurrentUser } from '../api/types';
import { AuthProvider } from '../auth/AuthProvider';
import { DevicePage } from './DevicePage';

const mocks = vi.hoisted(() => ({
  currentUser: vi.fn(),
  registerDevice: vi.fn(),
}));

vi.mock('../api/auth', () => ({
  currentUser: mocks.currentUser,
  login: vi.fn(),
  logout: vi.fn(),
}));

vi.mock('../api/webauthn', () => ({
  registerDevice: mocks.registerDevice,
}));

function employee(deviceStatus: CurrentUser['device']['status']): CurrentUser {
  return {
    id: 'e1',
    employeeNumber: 'AD-0001',
    fullName: 'Ada Okafor',
    email: 'ada.okafor@adesa.example',
    mustChangePassword: false,
    role: 'EMPLOYEE',
    device: {
      status: deviceStatus,
      label: null,
      registeredAt: null,
      approvedAt: null,
      revokedAt: null,
      revokedReason: null,
    },
  };
}

async function renderDevicePage() {
  const view = render(
    <MemoryRouter initialEntries={['/device']}>
      <AuthProvider>
        <DevicePage />
      </AuthProvider>
    </MemoryRouter>,
  );
  await screen.findByRole('heading', { name: 'Device', level: 1 });
  return view;
}

describe('device setup', () => {
  beforeEach(() => {
    mocks.currentUser.mockResolvedValue(employee('NONE'));
    mocks.registerDevice.mockReset();
  });

  it('offers the registration when no device is registered', async () => {
    await renderDevicePage();

    expect(screen.getByRole('button', { name: 'Register this device' })).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.getByRole('region', { name: 'Device' })).toHaveTextContent('No device registered'),
    );
  });

  it('waits for the administrator approval after a registration', async () => {
    mocks.registerDevice.mockResolvedValue({
      status: 'PENDING_APPROVAL',
      label: 'Office phone',
      registeredAt: '2026-10-21T10:00:00Z',
      approvedAt: null,
      revokedAt: null,
      revokedReason: null,
    });
    const user = userEvent.setup();

    await renderDevicePage();

    await user.type(screen.getByLabelText('Device name (optional)'), 'Office phone');
    await user.click(screen.getByRole('button', { name: 'Register this device' }));

    await waitFor(() => expect(mocks.registerDevice).toHaveBeenCalledWith('Office phone'));
    expect(await screen.findByRole('status')).toHaveTextContent(/administrator has to approve/i);
    expect(screen.getByRole('heading', { name: 'Waiting for approval' })).toBeInTheDocument();
  });

  it('explains a refused registration', async () => {
    mocks.registerDevice.mockRejectedValue(
      new ApiError(409, 'An approved device is already registered for this account.'),
    );
    const user = userEvent.setup();

    await renderDevicePage();
    await user.click(screen.getByRole('button', { name: 'Register this device' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'An approved device is already registered for this account.',
    );
  });

  it('does not offer a registration for an approved device', async () => {
    mocks.currentUser.mockResolvedValue(employee('APPROVED'));

    await renderDevicePage();

    expect(screen.queryByRole('button', { name: 'Register this device' })).toBeNull();
    expect(screen.getByRole('heading', { name: 'Approved device' })).toBeInTheDocument();
  });
});
