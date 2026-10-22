import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '../api/client';
import { AuthProvider } from '../auth/AuthProvider';
import { LoginPage } from './LoginPage';

const mocks = vi.hoisted(() => ({
  currentUser: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
}));

vi.mock('../api/auth', () => ({
  currentUser: mocks.currentUser,
  login: mocks.login,
  logout: mocks.logout,
}));

function renderLoginPage() {
  return render(
    <MemoryRouter initialEntries={['/login']}>
      <AuthProvider>
        <LoginPage />
      </AuthProvider>
    </MemoryRouter>,
  );
}

describe('login page', () => {
  beforeEach(() => {
    mocks.currentUser.mockRejectedValue(new ApiError(401, 'Not signed in.'));
    mocks.login.mockReset();
  });

  it('offers a sign-in form and no public registration', async () => {
    renderLoginPage();

    expect(await screen.findByLabelText('Work email')).toBeInTheDocument();
    expect(screen.getByLabelText('Password')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /register|sign up|create account/i })).toBeNull();
  });

  it('sends the credentials to the backend', async () => {
    const user = {
      id: 'e1',
      employeeNumber: 'AD-0001',
      fullName: 'Ada Okafor',
      email: 'ada.okafor@adesa.example',
      mustChangePassword: false,
      role: 'EMPLOYEE' as const,
      device: {
        status: 'APPROVED' as const,
        label: null,
        registeredAt: null,
        approvedAt: null,
        revokedAt: null,
        revokedReason: null,
      },
    };
    mocks.login.mockResolvedValue(user);
    const userEventInstance = userEvent.setup();

    renderLoginPage();

    await userEventInstance.type(await screen.findByLabelText('Work email'), 'ada.okafor@adesa.example');
    await userEventInstance.type(screen.getByLabelText('Password'), 'S3cret-passphrase');
    await userEventInstance.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(mocks.login).toHaveBeenCalledWith('ada.okafor@adesa.example', 'S3cret-passphrase');
  });

  it('shows an understandable error when the credentials are refused', async () => {
    mocks.login.mockRejectedValue(new ApiError(401, 'Invalid email or password.'));
    const userEventInstance = userEvent.setup();

    renderLoginPage();

    await userEventInstance.type(await screen.findByLabelText('Work email'), 'ada.okafor@adesa.example');
    await userEventInstance.type(screen.getByLabelText('Password'), 'wrong');
    await userEventInstance.click(screen.getByRole('button', { name: 'Sign in' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Email or password is incorrect.');
  });

  it('explains a refusal caused by the office-network requirement', async () => {
    mocks.login.mockRejectedValue(
      new ApiError(403, 'Attendance is only available from the approved ADESA office network.'),
    );
    const userEventInstance = userEvent.setup();

    renderLoginPage();

    await userEventInstance.type(await screen.findByLabelText('Work email'), 'ada.okafor@adesa.example');
    await userEventInstance.type(screen.getByLabelText('Password'), 'S3cret-passphrase');
    await userEventInstance.click(screen.getByRole('button', { name: 'Sign in' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('approved ADESA office network');
  });
});
