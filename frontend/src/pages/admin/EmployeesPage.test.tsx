import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { Employee } from '../../api/types';
import { AuthProvider } from '../../auth/AuthProvider';
import { EmployeesPage } from './EmployeesPage';

const mocks = vi.hoisted(() => ({
  currentUser: vi.fn(),
  fetchEmployees: vi.fn(),
  createEmployee: vi.fn(),
  updateEmployee: vi.fn(),
  reactivateEmployee: vi.fn(),
}));

vi.mock('../../api/auth', () => ({
  currentUser: mocks.currentUser,
  login: vi.fn(),
  logout: vi.fn(),
}));

vi.mock('../../api/admin', () => ({
  fetchEmployees: mocks.fetchEmployees,
  createEmployee: mocks.createEmployee,
  updateEmployee: mocks.updateEmployee,
  reactivateEmployee: mocks.reactivateEmployee,
}));

function session(role: 'HR' | 'ADMIN') {
  return {
    id: 'u1',
    employeeNumber: role === 'HR' ? 'HR-001' : 'ADMIN-001',
    fullName: role === 'HR' ? 'Grace Eze' : 'Site Administrator',
    email: `${role.toLowerCase()}@adesa.test`,
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

function employee(overrides: Partial<Employee> = {}): Employee {
  return {
    id: 'e1',
    employeeNumber: 'EMP-001',
    fullName: 'Amina Bello',
    email: 'amina.bello@adesa.test',
    role: 'EMPLOYEE',
    isActive: true,
    mustChangePassword: false,
    createdAt: '2026-10-01T08:00:00Z',
    updatedAt: '2026-10-01T08:00:00Z',
    ...overrides,
  };
}

async function renderPage() {
  const view = render(
    <MemoryRouter initialEntries={['/employees']}>
      <AuthProvider>
        <EmployeesPage />
      </AuthProvider>
    </MemoryRouter>,
  );
  await screen.findByRole('heading', { name: 'Employees', level: 1 });
  return view;
}

describe('the employee roster', () => {
  beforeEach(() => {
    mocks.currentUser.mockReset();
    mocks.fetchEmployees.mockReset();
    mocks.createEmployee.mockReset();
    mocks.updateEmployee.mockReset();
    mocks.reactivateEmployee.mockReset();
    mocks.fetchEmployees.mockResolvedValue([employee()]);
  });

  it('lists the employees', async () => {
    mocks.currentUser.mockResolvedValue(session('HR'));

    await renderPage();

    expect(await screen.findByText('Amina Bello')).toBeInTheDocument();
    expect(screen.getByText('amina.bello@adesa.test')).toBeInTheDocument();
  });

  it('gives HR no account management', async () => {
    mocks.currentUser.mockResolvedValue(session('HR'));

    await renderPage();

    await screen.findByText('Amina Bello');
    expect(screen.queryByRole('button', { name: 'Add employee' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Deactivate' })).toBeNull();
  });

  it('gives an administrator the account management', async () => {
    mocks.currentUser.mockResolvedValue(session('ADMIN'));

    await renderPage();

    expect(await screen.findByRole('button', { name: 'Add employee' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Deactivate' })).toBeInTheDocument();
  });

  it('creates an employee account', async () => {
    mocks.currentUser.mockResolvedValue(session('ADMIN'));
    mocks.createEmployee.mockResolvedValue(employee({ id: 'e2' }));
    const user = userEvent.setup();

    await renderPage();
    await user.click(await screen.findByRole('button', { name: 'Add employee' }));

    await user.click(screen.getByLabelText('Employee number'));
    await user.paste('EMP-002');
    await user.click(screen.getByLabelText('Full name'));
    await user.paste('Chidi Okafor');
    await user.click(screen.getByLabelText('Email'));
    await user.paste('chidi.okafor@adesa.test');
    await user.click(screen.getByLabelText(/Initial password/i));
    await user.paste('a-very-long-password1');
    await user.click(screen.getByRole('button', { name: 'Create the account' }));

    await waitFor(() => {
      expect(mocks.createEmployee).toHaveBeenCalledWith({
        employeeNumber: 'EMP-002',
        fullName: 'Chidi Okafor',
        email: 'chidi.okafor@adesa.test',
        password: 'a-very-long-password1',
        role: 'EMPLOYEE',
      });
    });
  });

  it('deactivates an employee', async () => {
    mocks.currentUser.mockResolvedValue(session('ADMIN'));
    mocks.updateEmployee.mockResolvedValue(employee({ isActive: false }));
    const user = userEvent.setup();

    await renderPage();
    await user.click(await screen.findByRole('button', { name: 'Deactivate' }));

    await waitFor(() => {
      expect(mocks.updateEmployee).toHaveBeenCalledWith('e1', { isActive: false });
    });
  });

  it('never offers a Deactivate action for an administrator account', async () => {
    mocks.currentUser.mockResolvedValue(session('ADMIN'));
    mocks.fetchEmployees.mockResolvedValue([
      employee({ id: 'a1', fullName: 'Site Administrator', role: 'ADMIN', isActive: true }),
    ]);

    await renderPage();

    await screen.findByText('Site Administrator');
    expect(screen.queryByRole('button', { name: 'Deactivate' })).toBeNull();
    expect(screen.getByText('Administrators cannot be deactivated.')).toBeInTheDocument();
  });

  it('offers a Reactivate action for an inactive administrator and confirms first', async () => {
    mocks.currentUser.mockResolvedValue(session('ADMIN'));
    mocks.fetchEmployees.mockResolvedValue([
      employee({ id: 'a1', fullName: 'Site Administrator', role: 'ADMIN', isActive: false }),
    ]);
    mocks.reactivateEmployee.mockResolvedValue(
      employee({ id: 'a1', fullName: 'Site Administrator', role: 'ADMIN', isActive: true }),
    );
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
    const user = userEvent.setup();

    await renderPage();
    await user.click(await screen.findByRole('button', { name: 'Reactivate' }));

    expect(confirmSpy).toHaveBeenCalled();
    await waitFor(() => {
      expect(mocks.reactivateEmployee).toHaveBeenCalledWith('a1');
    });
    expect(mocks.updateEmployee).not.toHaveBeenCalled();
  });

  it('does not reactivate when the administrator cancels the confirmation', async () => {
    mocks.currentUser.mockResolvedValue(session('ADMIN'));
    mocks.fetchEmployees.mockResolvedValue([
      employee({ id: 'a1', fullName: 'Site Administrator', role: 'ADMIN', isActive: false }),
    ]);
    vi.spyOn(window, 'confirm').mockReturnValue(false);
    const user = userEvent.setup();

    await renderPage();
    await user.click(await screen.findByRole('button', { name: 'Reactivate' }));

    expect(mocks.reactivateEmployee).not.toHaveBeenCalled();
  });
});
