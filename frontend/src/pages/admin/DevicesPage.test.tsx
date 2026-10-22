import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { AdminDevice } from '../../api/types';
import { AuthProvider } from '../../auth/AuthProvider';
import { DevicesPage } from './DevicesPage';

const mocks = vi.hoisted(() => ({
  currentUser: vi.fn(),
  fetchDevices: vi.fn(),
  approveDevice: vi.fn(),
  revokeDevice: vi.fn(),
  replaceDevice: vi.fn(),
}));

vi.mock('../../api/auth', () => ({
  currentUser: mocks.currentUser,
  login: vi.fn(),
  logout: vi.fn(),
}));

vi.mock('../../api/admin', () => ({
  fetchDevices: mocks.fetchDevices,
  approveDevice: mocks.approveDevice,
  revokeDevice: mocks.revokeDevice,
  replaceDevice: mocks.replaceDevice,
}));

const ADMIN = {
  id: 'a1',
  employeeNumber: 'ADMIN-001',
  fullName: 'Site Administrator',
  email: 'admin@adesa.test',
  mustChangePassword: false,
  role: 'ADMIN' as const,
  device: {
    status: 'APPROVED' as const,
    label: null,
    registeredAt: null,
    approvedAt: null,
    revokedAt: null,
    revokedReason: null,
  },
};

function device(overrides: Partial<AdminDevice> = {}): AdminDevice {
  return {
    id: 'd1',
    employeeId: 'e1',
    employeeNumber: 'EMP-001',
    employeeName: 'Amina Bello',
    label: 'Office phone',
    status: 'PENDING_APPROVAL',
    createdAt: '2026-10-21T10:00:00Z',
    approvedAt: null,
    revokedAt: null,
    revokedReason: null,
    ...overrides,
  };
}

async function renderPage() {
  const view = render(
    <MemoryRouter initialEntries={['/devices']}>
      <AuthProvider>
        <DevicesPage />
      </AuthProvider>
    </MemoryRouter>,
  );
  await screen.findByRole('heading', { name: 'Devices', level: 1 });
  return view;
}

describe('device administration', () => {
  beforeEach(() => {
    mocks.currentUser.mockResolvedValue(ADMIN);
    mocks.fetchDevices.mockReset();
    mocks.approveDevice.mockReset();
    mocks.revokeDevice.mockReset();
    mocks.replaceDevice.mockReset();
  });

  it('lists the devices that are waiting for approval', async () => {
    mocks.fetchDevices.mockResolvedValue([device()]);

    await renderPage();

    expect(await screen.findByText('Amina Bello')).toBeInTheDocument();
    expect(mocks.fetchDevices).toHaveBeenCalledWith('PENDING_APPROVAL');
    expect(screen.getByRole('button', { name: 'Approve' })).toBeInTheDocument();
  });

  it('approves a pending device', async () => {
    mocks.fetchDevices.mockResolvedValue([device()]);
    mocks.approveDevice.mockResolvedValue(device({ status: 'APPROVED' }));
    const user = userEvent.setup();

    await renderPage();
    await user.click(await screen.findByRole('button', { name: 'Approve' }));

    await waitFor(() => {
      expect(mocks.approveDevice).toHaveBeenCalledWith('d1');
    });
    expect(await screen.findByText('The device of Amina Bello is approved.')).toBeInTheDocument();
  });

  it('offers revoke and replace only for an approved device', async () => {
    mocks.fetchDevices.mockResolvedValue([device({ status: 'APPROVED' })]);

    await renderPage();

    expect(await screen.findByRole('button', { name: 'Revoke' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Replace' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Approve' })).toBeNull();
  });

  it('asks for a reason before a device is revoked', async () => {
    mocks.fetchDevices.mockResolvedValue([device({ status: 'APPROVED' })]);
    const user = userEvent.setup();

    await renderPage();
    await user.click(await screen.findByRole('button', { name: 'Revoke' }));

    await user.click(screen.getByRole('button', { name: 'Revoke the device' }));

    expect(await screen.findByText(/Please give a short reason/)).toBeInTheDocument();
    expect(mocks.revokeDevice).not.toHaveBeenCalled();
  });

  it('revokes a device with a reason', async () => {
    mocks.fetchDevices.mockResolvedValue([device({ status: 'APPROVED' })]);
    mocks.revokeDevice.mockResolvedValue(device({ status: 'REVOKED' }));
    const user = userEvent.setup();

    await renderPage();
    await user.click(await screen.findByRole('button', { name: 'Revoke' }));
    await user.type(screen.getByLabelText('Reason'), 'The phone was lost');
    await user.click(screen.getByRole('button', { name: 'Revoke the device' }));

    await waitFor(() => {
      expect(mocks.revokeDevice).toHaveBeenCalledWith('d1', 'The phone was lost');
    });
  });

  it('changes the list with the status filter', async () => {
    mocks.fetchDevices.mockResolvedValue([]);
    const user = userEvent.setup();

    await renderPage();
    await user.click(screen.getByRole('button', { name: 'Approved' }));

    await waitFor(() => {
      expect(mocks.fetchDevices).toHaveBeenCalledWith('APPROVED');
    });
  });
});
