export const mediaRoot = '/api/v1/media';

export type MediaRow = {
  id: string;
  revision?: number;
  archived_at?: string | null;
  [key: string]: unknown;
};
export type Register = {
  category: 'ADMIN' | 'BUSINESS_MANAGER' | 'COO' | 'OPERATOR';
  boards: MediaRow[];
  parties: MediaRow[];
  managers: { id: string; name: string }[];
  contracts: MediaRow[];
  obligations: MediaRow[];
  transactions: MediaRow[];
  audit: MediaRow[];
};
export type Entity = 'party' | 'board' | 'contract' | 'obligation' | 'payment';
export type Option = { value: string; label: string };

export const text = (row: MediaRow | undefined, key: string, fallback = ''): string => {
  const value = row?.[key];
  return typeof value === 'number' || typeof value === 'string' ? String(value) : fallback;
};
export const label = (value: string) => value.toLowerCase().replaceAll('_', ' ').replace(/^./, c => c.toUpperCase());
export const money = (value: string | number | undefined) => value === undefined || value === '' ? '—'
  : new Intl.NumberFormat('en-NG', { style: 'currency', currency: 'NGN' }).format(Number(value));

export function lagosToday() {
  const parts = new Intl.DateTimeFormat('en', {
    timeZone: 'Africa/Lagos', year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(new Date());
  return ['year', 'month', 'day'].map(type => parts.find(part => part.type === type)?.value).join('-');
}

export function contractStatus(row: MediaRow, today: string) {
  if (row.archived_at) return 'ARCHIVED';
  if (!row.confirmed) return 'DRAFT';
  if (row.superseded) return 'RENEWED';
  if (text(row, 'end_date') < today) return 'EXPIRED';
  if (text(row, 'start_date') > today) return 'UPCOMING';
  const soon = new Date(`${today}T12:00:00Z`);
  soon.setUTCDate(soon.getUTCDate() + 30);
  return text(row, 'end_date') <= soon.toISOString().slice(0, 10) ? 'EXPIRING' : 'ACTIVE';
}

export function paymentStatus(row: MediaRow, today: string) {
  if (Number(row.balance) === 0) return 'PAID';
  if (text(row, 'due_date') && text(row, 'due_date') < today) return 'OVERDUE';
  if (Number(row.paid) > 0) return 'PARTIALLY_PAID';
  return row.invoice_sent ? 'INVOICE_SENT' : 'AWAITING_PAYMENT';
}
