import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, expect, it, vi } from 'vitest';
import { MediaWorkspace } from './MediaWorkspace';

const request = vi.hoisted(() => vi.fn());
vi.mock('../../api/client', () => ({ apiRequest: request, apiDownload: vi.fn() }));
beforeEach(() => { request.mockReset(); });

function setup(category: string) {
  request.mockImplementation(async (path: string) => {
    if (path.endsWith('/access')) return { allowed: true, category };
    if (path.endsWith('/reminders/plan')) return { upcoming: [], verifiedRecipients: 0 };
    if (path.endsWith('/reminders') || path.endsWith('/imports')) return [];
    return { category, boards: [{ id: 'board-1', revision: 1, code: 'DEMO-001', status: 'RUNNING' }],
      parties: [], managers: [], contracts: [], obligations: [], transactions: [], audit: [] };
  });
  render(<MemoryRouter initialEntries={['/media/boards?section=boards']}><MediaWorkspace /></MemoryRouter>);
}

it('allows an operator to edit a board and hides archive and oversight actions', async () => {
  setup('OPERATOR');
  const edit = await screen.findByRole('button', {name:'Edit'});
  expect(screen.queryByRole('button', {name:'Archive'})).not.toBeInTheDocument();
  expect(screen.queryByRole('link', {name:'Audit'})).not.toBeInTheDocument();
  const scrollIntoView = vi.fn();
  const original = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'scrollIntoView');
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: scrollIntoView });
  try {
    await userEvent.setup().click(edit);
    expect(screen.getByRole('form', {name:'Edit board'})).toHaveFormValues({code:'DEMO-001'});
    expect(scrollIntoView).toHaveBeenCalledWith({ block: 'start', behavior: 'smooth' });
    expect(screen.getByLabelText('Board ID')).toHaveFocus();
  } finally {
    if (original) Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', original);
    else Reflect.deleteProperty(HTMLElement.prototype, 'scrollIntoView');
  }
});

it('asks the business manager to confirm archive and keeps permanent deletion out of the form', async () => {
  setup('BUSINESS_MANAGER');
  await userEvent.setup().click(await screen.findByRole('button', {name:'Archive'}));
  expect(screen.getByRole('checkbox', {name:/dependent records and history/})).toBeRequired();
  expect(screen.queryByRole('button', {name:'Permanently delete'})).not.toBeInTheDocument();
});


it('shows newly staged boards on the dashboard without a register entry and skips linked source rows', async () => {
  request.mockImplementation(async (path: string) => {
    if (path.endsWith('/access')) return { allowed: true, category: 'OPERATOR' };
    if (path.endsWith('/reminders/plan')) return { upcoming: [], verifiedRecipients: 0 };
    if (path.endsWith('/reminders')) return [];
    if (path.includes('/reports/PROFITABILITY/preview')) return { headers: [], rows: [] };
    if (path.endsWith('/imports')) return [{ id: 'latest', filename: 'Media.xlsx', created_at: '2026-11-23' }];
    if (path.endsWith('/reviews')) return [{ row: 2, location: 'Reviewed location', vendorApproved: true }];
    if (path.endsWith('/links')) return { 3: 'board-1' };
    if (path.endsWith('/imports/latest')) return { sheets: [], findings: [], drafts: [
      { row: 2, values: { 'OOH Location': 'Original location' }, operationalNotes: [], dates: {}, rates: {} },
      { row: 3, values: { 'OOH Location': 'Already linked row' }, operationalNotes: [], dates: {}, rates: {} },
    ] };
    return { category: 'OPERATOR', boards: [{ id: 'board-1', revision: 1, code: 'DEMO-001', status: 'RUNNING' }],
      parties: [], managers: [], contracts: [], obligations: [], transactions: [], audit: [] };
  });
  render(<MemoryRouter initialEntries={['/media/boards']}><MediaWorkspace /></MemoryRouter>);
  expect(await screen.findByText('Reviewed location')).toBeInTheDocument();
  expect(screen.getByText('Vendor date approved')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: 'Review board' })).toHaveAttribute('href', '/media/boards?section=imports&row=2');
  expect(screen.queryByText('Already linked row')).not.toBeInTheDocument();
});
