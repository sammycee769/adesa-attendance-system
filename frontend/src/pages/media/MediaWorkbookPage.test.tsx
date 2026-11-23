import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, it, vi } from 'vitest';
import { MediaWorkbookPage } from './MediaWorkbookPage';

const request = vi.hoisted(() => vi.fn());
vi.mock('../../api/client', () => ({ apiRequest: request, apiDownload: vi.fn() }));

beforeEach(() => { request.mockReset(); });

it('withholds upload controls when the server denies module access', async () => {
  request.mockResolvedValue({ allowed: false });
  render(<MediaWorkbookPage />);
  expect(await screen.findByText(/do not have access/)).toBeInTheDocument();
  expect(screen.queryByLabelText(/Workbook \(/)).not.toBeInTheDocument();
  expect(request).toHaveBeenCalledTimes(1);
});

it('previews the original file before offering to stage it and shows both vendor expiry dates', async () => {
  request.mockImplementation(async (path: string) => {
    if (path.endsWith('/access')) return { allowed: true, category: 'OPERATOR' };
    if (path.endsWith('/preview')) return {
      sheets: [{ name: 'OOH Sheet', headerRow: 1, headers: { G: 'Vendor renewal expiry' }, rows: [{ number: 2, cells: [{ address: 'G2', type: 'NUMERIC', display: '30 September 2026', value: '46295', formula: 'SUM(A1)' }] }] }],
      drafts: [{
        row: 2, values: { Client: 'Test Client' }, rates: {}, operationalNotes: [],
        dates: { 'Expiration Date after renewal with vendors': '2026-09-30', 'Expiration Date with Vendor': '2026-10-01' },
      }],
      findings: [{ sheet: 'OOH Sheet', row: 2, column: 'I', code: 'VENDOR_EXPIRY_CONFLICT', message: 'Vendor expiry columns disagree.' }],
    };
    return [];
  });
  render(<MediaWorkbookPage />);
  const user = userEvent.setup();
  const input = await screen.findByLabelText(/Workbook \(/);
  await user.upload(input, new File(['fixture'], 'ADESA.xlsx'));
  const stage = screen.getByRole('button', { name: 'Save staging batch' });
  expect(stage).toBeDisabled();
  await user.click(screen.getByRole('button', { name: 'Preview workbook' }));
  expect(await screen.findByText('2026-09-30')).toBeInTheDocument();
  expect(screen.getByText('2026-10-01')).toBeInTheDocument();
  expect(screen.getByText(/Vendor expiry columns disagree/)).toBeInTheDocument();
  expect(stage).toBeEnabled();
  expect(screen.getByRole('heading', { name: 'Board status' })).toBeInTheDocument();
  expect(screen.getByRole('heading', { name: 'Reminder preview' })).toBeInTheDocument();
  expect(screen.getByText('Confirm vendor expiry dates')).toBeInTheDocument();
  expect(screen.getByRole('heading', { name: 'Items to review' })).toBeInTheDocument();
  await user.click(screen.getByText('View worksheet values'));
  expect(screen.getByText('30 September 2026')).toBeInTheDocument();
  expect(screen.queryByText('NUMERIC')).not.toBeInTheDocument();
  expect(screen.queryByText('SUM(A1)')).not.toBeInTheDocument();
  expect(request.mock.calls.some(([path, options]) => path.endsWith('/preview')
    && options.body.get('workbook').name === 'ADESA.xlsx')).toBe(true);
});

it('saves a selected vendor expiry against the staged row without changing the original evidence', async () => {
  request.mockImplementation(async (path: string, options?: { method?: string; body?: Record<string, unknown> }) => {
    if (path.endsWith('/access')) return { allowed: true, category: 'OPERATOR' };
    if (path.endsWith('/imports')) return [{ id: 'batch', filename: 'Client status.xlsx', created_at: '2026-11-23' }];
    if (path.endsWith('/reviews')) return [];
    if (path.endsWith('/links')) return {};
    if (path.endsWith('/reviews/2') && options?.method === 'PUT') return { ...options.body, row: 2, revision: 1 };
    return {
      sheets: [{ name: 'OOH Sheet', headerRow: 1, headers: {}, rows: [] }], findings: [],
      drafts: [{ row: 2, values: { 'OOH Location': 'Ring Road', Client: 'Example Client', 'Vendor Name': 'Example Vendor' },
        rates: {}, dates: { 'Expiration Date after renewal with vendors': '2026-12-10', 'Expiration Date with Vendor': '2026-12-20' },
        operationalNotes: [] }],
    };
  });
  render(<MediaWorkbookPage />);
  const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: 'Edit and approve dates' }));
  const dialog = screen.getByRole('form', { name: 'Review board' });
  expect(dialog).toHaveTextContent('2026-12-10');
  expect(dialog).toHaveTextContent('2026-12-20');
  await user.clear(screen.getByLabelText('Board location'));
  await user.type(screen.getByLabelText('Board location'), 'Ring Road North');
  const vendor = screen.getByLabelText('Reviewed vendor expiry');
  await user.type(vendor, '2026-12-20');
  await user.click(screen.getByRole('checkbox', { name: 'Approve vendor expiry' }));
  await user.click(screen.getByRole('button', { name: 'Save board review' }));
  expect(await screen.findByText('Board review saved.')).toBeInTheDocument();
  expect(request).toHaveBeenCalledWith('/api/v1/media/imports/batch/reviews/2', expect.objectContaining({
    method: 'PUT', body: expect.objectContaining({ location: 'Ring Road North', vendorExpiry: '2026-12-20', vendorApproved: true }),
  }));
  expect(screen.getByText('2026-12-10')).toBeInTheDocument();
});

it('starts guided setup on upload and asks for a payment due date only when an obligation is selected', async () => {
  const staged = { id: 'batch', inserted: 1, updated: 0, skipped: 0, flagged: 1 };
  request.mockImplementation(async (path: string, options?: { method?: string }) => {
    if (path.endsWith('/access')) return { allowed: true, category: 'OPERATOR' };
    if (path.endsWith('/imports')) return options?.method === 'POST' ? staged : [];
    if (path.endsWith('/reviews')) return [];
    if (path.endsWith('/links')) return {};
    return { sheets: [], findings: [], drafts: [{ row: 2, values: { 'OOH Location': 'Ring Road', Client: 'Test client',
      'Vendor Name': 'Test vendor', 'Client Status': 'PAID' }, rates: {}, dates: {}, operationalNotes: [] }] };
  });
  render(<MediaWorkbookPage register={{ category: 'OPERATOR', boards: [], parties: [], managers: [], contracts: [],
    obligations: [], transactions: [], audit: [] }} />);
  const user = userEvent.setup();
  await user.upload(await screen.findByLabelText(/Workbook \(/), new File(['test'], 'Media.xlsx'));
  expect(await screen.findByRole('form', { name: 'Complete workbook board' })).toBeInTheDocument();
  expect(screen.getByLabelText('Board location')).toHaveValue('Ring Road');
  expect(screen.getByText(/Workbook status note: PAID/)).toBeInTheDocument();
  expect(screen.queryByLabelText('Vendor payment due date')).not.toBeInTheDocument();
  await user.selectOptions(screen.getByLabelText('Vendor payment obligation'), 'true');
  expect(screen.getByLabelText('Vendor payment due date')).toBeRequired();
  expect(screen.getByLabelText('Vendor amount due (NGN)')).toBeRequired();
});

it('resumes a workbook staged before guided setup and skips boards already linked', async () => {
  request.mockImplementation(async (path: string) => {
    if (path.endsWith('/access')) return { allowed: true, category: 'OPERATOR' };
    if (path.endsWith('/imports')) return [{ id: 'old-batch', filename: 'Original.xlsx', created_at: '2026-11-20' }];
    if (path.endsWith('/reviews')) return [];
    if (path.endsWith('/links')) return { 2: 'registered-board' };
    return { sheets: [], findings: [], drafts: [
      { row: 2, values: { 'OOH Location': 'Linked board' }, rates: {}, dates: {}, operationalNotes: [] },
      { row: 3, values: { 'OOH Location': 'Needs setup' }, rates: {}, dates: {}, operationalNotes: [] },
    ] };
  });
  render(<MediaWorkbookPage register={{ category: 'OPERATOR', boards: [], parties: [], managers: [],
    contracts: [], obligations: [], transactions: [], audit: [] }} />);
  const user = userEvent.setup();
  expect(await screen.findByRole('form', { name: 'Complete workbook board' })).toHaveTextContent('row 3');
  expect(screen.getByLabelText('Board location')).toHaveValue('Needs setup');
  await user.click(screen.getByRole('button', { name: 'Finish later' }));
  expect(screen.queryByRole('form', { name: 'Complete workbook board' })).not.toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Resume guided setup' }));
  expect(screen.getByLabelText('Board location')).toHaveValue('Needs setup');
});
