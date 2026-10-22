import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { AuditLogEntry } from '../../api/types';
import { AuthProvider } from '../../auth/AuthProvider';
import { AuditPage } from './AuditPage';

const mocks = vi.hoisted(() => ({
  currentUser: vi.fn(),
  fetchAuditLogs: vi.fn(),
}));

vi.mock('../../api/auth', () => ({
  currentUser: mocks.currentUser,
  login: vi.fn(),
  logout: vi.fn(),
}));

vi.mock('../../api/admin', () => ({
  fetchAuditLogs: mocks.fetchAuditLogs,
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

function entry(overrides: Partial<AuditLogEntry> = {}): AuditLogEntry {
  return {
    id: 'a1',
    actorId: 'e1',
    actorEmployeeNumber: 'EMP-001',
    actorName: 'Amina Bello',
    action: 'ATTENDANCE_SIGNED_IN',
    targetType: 'ATTENDANCE_RECORD',
    targetId: 'r1',
    details: { attendanceDate: '2026-10-20' },
    createdAt: '2026-10-20T07:30:00Z',
    ...overrides,
  };
}

function page(records: AuditLogEntry[]) {
  return { records, page: 0, size: 50, totalElements: records.length, totalPages: 1 };
}

async function renderPage() {
  const view = render(
    <MemoryRouter initialEntries={['/audit']}>
      <AuthProvider>
        <AuditPage />
      </AuthProvider>
    </MemoryRouter>,
  );
  await screen.findByRole('heading', { name: 'Audit trail', level: 1 });
  return view;
}

describe('the audit trail', () => {
  beforeEach(() => {
    mocks.currentUser.mockReset();
    mocks.fetchAuditLogs.mockReset();
    mocks.fetchAuditLogs.mockResolvedValue(page([entry()]));
  });

  it('lists the audit entries', async () => {
    mocks.currentUser.mockResolvedValue(session('ADMIN'));

    await renderPage();

    // The action also appears in the filter list, so the row is found by the
    // employee that performed it.
    expect(await screen.findByText('Amina Bello')).toBeInTheDocument();
    expect((await screen.findAllByText('ATTENDANCE_SIGNED_IN')).length).toBeGreaterThan(0);
  });

  it('offers every action to an administrator', async () => {
    mocks.currentUser.mockResolvedValue(session('ADMIN'));

    await renderPage();

    await waitFor(() => {
      expect(mocks.fetchAuditLogs).toHaveBeenCalled();
    });
    const select = screen.getByLabelText('Action') as HTMLSelectElement;
    const options = Array.from(select.options).map((option) => option.value);
    expect(options).toContain('EMPLOYEE_PASSWORD_RESET');
    expect(options).toContain('ATTENDANCE_CORRECTED');
  });

  it('offers only the attendance and device actions to HR', async () => {
    mocks.currentUser.mockResolvedValue(session('HR'));

    await renderPage();

    await waitFor(() => {
      expect(mocks.fetchAuditLogs).toHaveBeenCalled();
    });
    const select = screen.getByLabelText('Action') as HTMLSelectElement;
    const options = Array.from(select.options).map((option) => option.value);
    expect(options).toContain('ATTENDANCE_CORRECTED');
    expect(options).not.toContain('EMPLOYEE_PASSWORD_RESET');
    expect(options).not.toContain('LOGIN_FAILED');
  });

  it('filters the entries by action', async () => {
    mocks.currentUser.mockResolvedValue(session('ADMIN'));
    const user = userEvent.setup();

    await renderPage();
    await user.selectOptions(screen.getByLabelText('Action'), 'ATTENDANCE_CORRECTED');

    await waitFor(() => {
      expect(mocks.fetchAuditLogs).toHaveBeenCalledWith(
        expect.objectContaining({ action: 'ATTENDANCE_CORRECTED' }),
      );
    });
  });
});
