import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { App } from '../App';
import { ApiError } from '../api/client';
import { AuthProvider } from './AuthProvider';

const mocks = vi.hoisted(() => ({
  currentUser: vi.fn(),
}));

vi.mock('../api/auth', () => ({
  currentUser: mocks.currentUser,
  login: vi.fn(),
  logout: vi.fn(),
}));

vi.mock('../api/attendance', () => ({
  fetchToday: vi.fn().mockResolvedValue({
    attendanceDate: '2026-10-21',
    timezone: 'Africa/Lagos',
    state: 'NOT_SIGNED_IN',
    signInAt: null,
    signOutAt: null,
    canSignIn: true,
    canSignOut: false,
    deviceStatus: 'APPROVED',
  }),
  fetchHistory: vi.fn().mockResolvedValue({
    records: [],
    page: 0,
    size: 20,
    totalElements: 0,
    totalPages: 1,
  }),
  signIn: vi.fn(),
  signOut: vi.fn(),
}));

vi.mock('../api/webauthn', () => ({
  authenticateWithDevice: vi.fn(),
}));

describe('protected screens', () => {
  beforeEach(() => {
    mocks.currentUser.mockReset();
  });

  it('redirects to the login page when there is no session', async () => {
    mocks.currentUser.mockRejectedValue(new ApiError(401, 'Not signed in.'));

    render(
      <MemoryRouter initialEntries={['/']}>
        <AuthProvider>
          <App />
        </AuthProvider>
      </MemoryRouter>,
    );

    expect(await screen.findByRole('button', { name: 'Sign in' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Attendance', level: 1 })).toBeNull();
  });

  it('redirects to the login page when a protected URL is opened directly', async () => {
    mocks.currentUser.mockRejectedValue(new ApiError(401, 'Not signed in.'));

    render(
      <MemoryRouter initialEntries={['/device']}>
        <AuthProvider>
          <App />
        </AuthProvider>
      </MemoryRouter>,
    );

    expect(await screen.findByRole('button', { name: 'Sign in' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Device', level: 1 })).toBeNull();
  });

  it('shows the dashboard for a signed-in employee', async () => {
    mocks.currentUser.mockResolvedValue({
      id: 'e1',
      employeeNumber: 'AD-0001',
      fullName: 'Ada Okafor',
      email: 'ada.okafor@adesa.example',
      mustChangePassword: false,
      role: 'EMPLOYEE',
      device: {
        status: 'APPROVED',
        label: null,
        registeredAt: null,
        approvedAt: null,
        revokedAt: null,
        revokedReason: null,
      },
    });

    render(
      <MemoryRouter initialEntries={['/']}>
        <AuthProvider>
          <App />
        </AuthProvider>
      </MemoryRouter>,
    );

    expect(await screen.findByRole('heading', { name: 'Attendance', level: 1 })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Log out' })).toBeInTheDocument();
  });
});
