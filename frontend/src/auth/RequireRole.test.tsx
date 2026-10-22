import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { AuthProvider } from './AuthProvider';
import { RequireRole } from './RequireRole';

const mocks = vi.hoisted(() => ({
  currentUser: vi.fn(),
}));

vi.mock('../api/auth', () => ({
  currentUser: mocks.currentUser,
  login: vi.fn(),
  logout: vi.fn(),
}));

function session(role: 'EMPLOYEE' | 'HR' | 'ADMIN') {
  return {
    id: 'u1',
    employeeNumber: 'AD-0001',
    fullName: 'Ada Okafor',
    email: 'ada.okafor@adesa.test',
    mustChangePassword: false,
    role,
    device: {
      status: 'APPROVED' as const,
      label: null,
      registeredAt: null,
      approvedAt: null,
      revokedAt: null,
      revokedReason: null,
    },
  };
}

function renderGuard(roles: ('EMPLOYEE' | 'HR' | 'ADMIN')[]) {
  return render(
    <MemoryRouter initialEntries={['/protected']}>
      <AuthProvider>
        <Routes>
          <Route path="/login" element={<p>Login page</p>} />
          <Route
            path="/protected"
            element={
              <RequireRole roles={roles}>
                <p>Protected area</p>
              </RequireRole>
            }
          />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  );
}

describe('the role guard', () => {
  beforeEach(() => {
    mocks.currentUser.mockReset();
  });

  it('shows the area to a role that may use it', async () => {
    mocks.currentUser.mockResolvedValue(session('HR'));

    renderGuard(['HR', 'ADMIN']);

    expect(await screen.findByText('Protected area')).toBeInTheDocument();
  });

  it('refuses a role that may not use it', async () => {
    mocks.currentUser.mockResolvedValue(session('EMPLOYEE'));

    renderGuard(['HR', 'ADMIN']);

    expect(await screen.findByRole('alert')).toHaveTextContent('Not available for your role');
    expect(screen.queryByText('Protected area')).toBeNull();
  });

  it('sends a visitor without a session to the login page', async () => {
    mocks.currentUser.mockRejectedValue(new Error('Not signed in.'));

    renderGuard(['ADMIN']);

    expect(await screen.findByText('Login page')).toBeInTheDocument();
    expect(screen.queryByText('Protected area')).toBeNull();
  });
});
