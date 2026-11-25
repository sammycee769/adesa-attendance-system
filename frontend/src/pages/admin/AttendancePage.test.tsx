import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, expect, it, vi } from 'vitest';
import type { AttendanceDay, DailyAttendanceReport, DailyAttendanceRow } from '../../api/types';
import { AttendancePage } from './AttendancePage';

const mocks = vi.hoisted(() => ({
  fetchDailyAttendance: vi.fn(), exportAttendancePdf: vi.fn(), exportAttendanceCsv: vi.fn(), correctAttendance: vi.fn(),
}));
vi.mock('../../api/admin', () => mocks);

const signed: DailyAttendanceRow = {
  recordId: 'r1', employeeId: 'e1', employeeNumber: 'EMP-001', employeeName: 'Amina Bello',
  subsidiary: 'ADESA', department: 'Operations', status: 'SIGNED_OUT', signInAt: '2026-11-25T07:30:00Z',
  signOutAt: '2026-11-25T16:00:00Z', workedMinutes: 510, expected: true, late: false, corrected: false, remarks: '',
};
const absent: DailyAttendanceRow = {
  ...signed, recordId: null, employeeId: 'e2', employeeNumber: 'EMP-002', employeeName: 'Grace Eze',
  status: 'ABSENT', signInAt: null, signOutAt: null, workedMinutes: null,
};
const summary = { workingDays: 1, expected: 2, present: 1, absent: 1, late: 0,
  signedOut: 1, yetToSignOut: 0, incomplete: 0, approvedAbsences: 0, expectedPresent: 1 };
const first: AttendanceDay = { date: '2026-11-25', employees: [signed, absent], summary };
const second: AttendanceDay = { ...first, date: '2026-11-24' };
const report: DailyAttendanceReport = { from: '2026-11-24', to: '2026-11-25', timezone: 'Africa/Lagos',
  page: 0, totalDays: 8, days: [first, second], summary };

beforeEach(() => {
  Object.values(mocks).forEach(mock => mock.mockReset());
  mocks.fetchDailyAttendance.mockResolvedValue(report);
});

function showPage() {
  render(<MemoryRouter><AttendancePage /></MemoryRouter>);
}

it('opens the latest day and shows absent staff with missing times', async () => {
  const user = userEvent.setup();
  showPage();
  const firstDay = await screen.findByRole('button', { name: 'Wednesday, 25 November 2026' });
  const secondDay = screen.getByRole('button', { name: 'Tuesday, 24 November 2026' });
  expect(firstDay).toHaveAttribute('aria-expanded','true');
  expect(secondDay).toHaveAttribute('aria-expanded','false');
  const row = screen.getByRole('row', { name: /Grace Eze/ });
  expect(within(row).getByText('Absent')).toBeInTheDocument();
  expect(within(row).getAllByText('–')).toHaveLength(5);
  expect(within(row).queryByRole('button')).toBeNull();
  await user.click(secondDay);
  expect(secondDay).toHaveAttribute('aria-expanded','true');
  expect(screen.getAllByRole('table')).toHaveLength(2);
});

it('sends selected date and employee filters and paginates by working day', async () => {
  const user = userEvent.setup();
  showPage();
  await screen.findByRole('button', { name: /Wednesday/ });
  await user.type(screen.getByLabelText('Employee name'),'Amina');
  await user.type(screen.getByLabelText('Subsidiary'),'ADESA');
  await user.type(screen.getByLabelText('Department'),'Operations');
  await user.selectOptions(screen.getByLabelText('Attendance status'),'SIGNED_OUT');
  await user.click(screen.getByRole('button', { name: 'Apply filters' }));
  await waitFor(() => expect(mocks.fetchDailyAttendance).toHaveBeenLastCalledWith(
    expect.objectContaining({ search:'Amina', subsidiary:'ADESA', department:'Operations', status:'SIGNED_OUT', page:0, size:7 }),
    expect.any(AbortSignal),
  ));
  await user.click(await screen.findByRole('button', { name: 'Older days' }));
  expect(mocks.fetchDailyAttendance).toHaveBeenLastCalledWith(expect.objectContaining({ page:1 }),expect.any(AbortSignal));
});

it('passes the exact displayed reporting period and applied filters to exports', async () => {
  const user = userEvent.setup();
  showPage();
  await screen.findByRole('button', { name: /Wednesday/ });
  await user.type(screen.getByLabelText('Subsidiary'),'ADESA');
  await user.click(screen.getByRole('button', { name: 'Apply filters' }));
  const download = await screen.findByRole('button', { name: 'Download PDF' });
  mocks.exportAttendancePdf.mockRejectedValue(new Error('simulated download failure'));
  await waitFor(() => expect(download).toBeEnabled());
  await user.click(download);
  expect(mocks.exportAttendancePdf).toHaveBeenCalledWith(expect.objectContaining({
    from:'2026-11-24',to:'2026-11-25',subsidiary:'ADESA',
  }));
});

it('requires a reason and refreshes daily totals after saving an existing correction', async () => {
  const user = userEvent.setup();
  showPage();
  await user.click(await screen.findByRole('button', { name: /Correct attendance for Amina/ }));
  await user.click(screen.getByRole('button', { name: 'Save the correction' }));
  expect(screen.getByText(/Please give a reason/)).toBeInTheDocument();
  expect(mocks.correctAttendance).not.toHaveBeenCalled();
  mocks.correctAttendance.mockResolvedValue({});
  await user.type(screen.getByLabelText('Reason'),'Verified with supervisor');
  await user.click(screen.getByRole('button', { name: 'Save the correction' }));
  expect(mocks.correctAttendance).toHaveBeenCalledWith('r1',expect.objectContaining({ reason:'Verified with supervisor' }));
  await waitFor(() => expect(mocks.fetchDailyAttendance).toHaveBeenCalledTimes(2));
});
