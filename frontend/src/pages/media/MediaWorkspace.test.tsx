import { render, screen, within } from '@testing-library/react';
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

it('shows client and vendor balances and records payments against the selected agreement', async () => {
  request.mockImplementation(async (path: string) => {
    if (path.endsWith('/access')) return { allowed: true, category: 'OPERATOR' };
    if (path.endsWith('/reminders/plan')) return { upcoming: [], verifiedRecipients: 3 };
    if (path.endsWith('/reminders') || path.endsWith('/imports')) return [];
    return {
      category: 'OPERATOR',
      boards: [{ id: 'board-1', code: 'DEMO-001', status: 'RUNNING' }],
      parties: [], managers: [], audit: [], obligations: [], transactions: [],
      contracts: [
        { id: 'client-1', board_id: 'board-1', kind: 'CLIENT', confirmed: true, end_date: '2026-12-31',
          invoice_sent: true, invoice_revision: 2, amount: 500, paid: 0, balance: 500 },
        { id: 'vendor-1', board_id: 'board-1', kind: 'VENDOR', confirmed: true, end_date: '2026-12-20',
          amount: 300, paid: 100, balance: 200 },
      ],
    };
  });
  render(<MemoryRouter initialEntries={['/media/boards?section=payments']}><MediaWorkspace /></MemoryRouter>);
  const user = userEvent.setup();
  expect(await screen.findByRole('cell', { name: 'Awaiting balance' })).toBeInTheDocument();
  expect(screen.getByRole('cell', { name: 'Part payment' })).toBeInTheDocument();
  const invoice = screen.getByRole('checkbox', { name: /Invoice sent for DEMO-001/ });
  expect(invoice).toBeChecked();
  await user.click(invoice);
  expect(request).toHaveBeenCalledWith('/api/v1/media/register/contract/invoice', {
    method: 'POST', body: { id: 'client-1', revision: 2, invoiceSent: false },
  });
  const vendorRow = screen.getByRole('row', { name: /DEMO-001 2026-12-20/ });
  const vendorPayment = within(vendorRow).getByRole('button', { name: 'Record payment' });
  await user.click(vendorPayment);
  expect(screen.getByText(/Vendor agreement · DEMO-001/)).toBeInTheDocument();
  expect(screen.getByLabelText('SLA link')).toBeRequired();
  await user.type(screen.getByLabelText('Amount (NGN)'), '25');
  await user.type(screen.getByLabelText('Payment reference'), 'receipt-25');
  await user.type(screen.getByLabelText('SLA link'), 'https://example.com/sla');
  await user.click(screen.getByRole('button', { name: 'Save' }));
  expect(request).toHaveBeenCalledWith('/api/v1/media/register/payment/record', {
    method: 'POST', body: expect.objectContaining({ contractId: 'vendor-1', amount: '25', slaLink: 'https://example.com/sla' }),
  });
});
