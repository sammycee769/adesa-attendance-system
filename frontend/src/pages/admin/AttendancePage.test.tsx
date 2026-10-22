import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { AdminAttendanceRecord, AttendanceOverviewEntry } from '../../api/types';
import { AuthProvider } from '../../auth/AuthProvider';
import { AttendancePage } from './AttendancePage';

const mocks = vi.hoisted(() => ({
  currentUser: vi.fn(),
  fetchAttendance: vi.fn(),
  fetchAttendanceOverview: vi.fn(),
  exportAttendancePdf: vi.fn(),
  correctAttendance: vi.fn(),
}));

vi.mock('../../api/auth', () => ({
  currentUser: mocks.currentUser,
  login: vi.fn(),
  logout: vi.fn(),
}));

vi.mock('../../api/admin', () => ({
  fetchAttendance: mocks.fetchAttendance,
  fetchAttendanceOverview: mocks.fetchAttendanceOverview,
  exportAttendancePdf: mocks.exportAttendancePdf,
  correctAttendance: mocks.correctAttendance,
}));

const HR = {
  id: 'h1',
  employeeNumber: 'HR-001',
  fullName: 'Grace Eze',
  email: 'grace.eze@adesa.test',
  mustChangePassword: false,
  role: 'HR' as const,
  device: {
    status: 'APPROVED' as const,
    label: null,
    registeredAt: null,
    approvedAt: null,
    revokedAt: null,
    revokedReason: null,
  },
};

function overviewEntry(overrides: Partial<AttendanceOverviewEntry> = {}): AttendanceOverviewEntry {
  return {
    employeeId: 'e1',
    employeeNumber: 'EMP-001',
    employeeName: 'Amina Bello',
    employeeRole: 'EMPLOYEE',
    state: 'SIGNED_IN',
    signInAt: '2026-10-20T07:30:00Z',
    signOutAt: null,
    corrected: false,
    ...overrides,
  };
}

function record(overrides: Partial<AdminAttendanceRecord> = {}): AdminAttendanceRecord {
  return {
    id: 'r1',
    employeeId: 'e1',
    employeeNumber: 'EMP-001',
    employeeName: 'Amina Bello',
    employeeRole: 'EMPLOYEE',
    attendanceDate: '2026-10-20',
    timezone: 'Africa/Lagos',
    state: 'SIGNED_IN',
    signInAt: '2026-10-20T07:30:00Z',
    signOutAt: null,
    corrected: false,
    correctionCount: 0,
    updatedAt: '2026-10-20T07:30:00Z',
    ...overrides,
  };
}

function page(records: AdminAttendanceRecord[]) {
  return { records, page: 0, size: 50, totalElements: records.length, totalPages: 1 };
}

async function renderPage() {
  const view = render(
    <MemoryRouter initialEntries={['/attendance']}>
      <AuthProvider>
        <AttendancePage />
      </AuthProvider>
    </MemoryRouter>,
  );
  await screen.findByRole('heading', { name: 'Attendance', level: 1 });
  return view;
}

describe('the attendance report', () => {
  beforeEach(() => {
    mocks.currentUser.mockResolvedValue(HR);
    mocks.fetchAttendance.mockReset();
    mocks.fetchAttendanceOverview.mockReset();
    mocks.exportAttendancePdf.mockReset();
    mocks.correctAttendance.mockReset();
    mocks.fetchAttendanceOverview.mockResolvedValue([overviewEntry()]);
    mocks.fetchAttendance.mockResolvedValue(page([record()]));
  });

  it('shows the daily overview', async () => {
    await renderPage();

    expect(await screen.findByText('Amina Bello')).toBeInTheDocument();
    expect(screen.getByText('Signed in')).toBeInTheDocument();
    expect(mocks.fetchAttendanceOverview).toHaveBeenCalled();
  });

  it('lists the records with the filters', async () => {
    const user = userEvent.setup();

    await renderPage();
    await user.click(screen.getByRole('button', { name: 'Records' }));

    expect(await screen.findByRole('button', { name: 'Correct' })).toBeInTheDocument();
    expect(mocks.fetchAttendance).toHaveBeenCalled();
  });

  it('refuses a correction without a reason', async () => {
    const user = userEvent.setup();

    await renderPage();
    await user.click(screen.getByRole('button', { name: 'Records' }));
    await user.click(await screen.findByRole('button', { name: 'Correct' }));
    await user.click(screen.getByRole('button', { name: 'Save the correction' }));

    expect(await screen.findByText(/Please give a reason/)).toBeInTheDocument();
    expect(mocks.correctAttendance).not.toHaveBeenCalled();
  });

  it('sends the correction with the reason and the times', async () => {
    mocks.correctAttendance.mockResolvedValue(record({ corrected: true, correctionCount: 1 }));
    const user = userEvent.setup();

    await renderPage();
    await user.click(screen.getByRole('button', { name: 'Records' }));
    await user.click(await screen.findByRole('button', { name: 'Correct' }));

    // The form is filled with the stored times (08:30 in the office timezone).
    const signIn = screen.getByLabelText('Sign in') as HTMLInputElement;
    expect(signIn.value).toBe('2026-10-20T08:30');

    await user.type(screen.getByLabelText('Reason'), 'Approved late arrival by the floor manager');
    await user.click(screen.getByRole('button', { name: 'Save the correction' }));

    await waitFor(() => {
      expect(mocks.correctAttendance).toHaveBeenCalledWith('r1', {
        signInAt: '2026-10-20T07:30:00.000Z',
        signOutAt: null,
        reason: 'Approved late arrival by the floor manager',
      });
    });
    expect(await screen.findByText(/The attendance record of Amina Bello was corrected/)).toBeInTheDocument();
  });

  it('exports the report as PDF', async () => {
    const createObjectURL = vi.fn(() => 'blob:report');
    const revokeObjectURL = vi.fn();
    vi.stubGlobal('URL', { ...URL, createObjectURL, revokeObjectURL });
    mocks.exportAttendancePdf.mockResolvedValue(new Blob(['%PDF-']));
    const user = userEvent.setup();

    await renderPage();
    await user.click(screen.getByRole('button', { name: 'Records' }));
    await user.click(await screen.findByRole('button', { name: 'Export as PDF' }));

    await waitFor(() => {
      expect(mocks.exportAttendancePdf).toHaveBeenCalled();
    });
    expect(createObjectURL).toHaveBeenCalled();
    vi.unstubAllGlobals();
  });

  it('shows the error of a refused correction', async () => {
    const { ApiError } = await import('../../api/client');
    mocks.correctAttendance.mockRejectedValue(new ApiError(409, 'The action conflicts with the current state.'));
    const user = userEvent.setup();

    await renderPage();
    await user.click(screen.getByRole('button', { name: 'Records' }));
    await user.click(await screen.findByRole('button', { name: 'Correct' }));
    await user.type(screen.getByLabelText('Reason'), 'Approved late arrival by the floor manager');
    await user.click(screen.getByRole('button', { name: 'Save the correction' }));

    expect(await screen.findByText(/conflicts with the current state/)).toBeInTheDocument();
  });
});
