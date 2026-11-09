import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Layout } from './Layout';

const auth = vi.hoisted(() => ({ role: 'ADMIN', signOut: vi.fn() }));
vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => ({ user: { role: auth.role, fullName: 'Admin With A Long Display Name', employeeNumber: 'ADMIN-001' }, signOut: auth.signOut }),
}));

function CurrentPage() { return <p data-testid="current-page">{useLocation().pathname}</p>; }
function showLayout() {
  return render(<MemoryRouter><Layout><CurrentPage /></Layout></MemoryRouter>);
}

describe('responsive application navigation', () => {
  beforeEach(() => { auth.role = 'ADMIN'; auth.signOut.mockReset(); });

  it('opens the menu and closes it with Escape, returning focus to the toggle', async () => {
    const user = userEvent.setup();
    showLayout();
    const menu = screen.getByRole('button', { name: 'Menu' });
    expect(menu).toHaveAttribute('aria-expanded', 'false');
    expect(menu).toHaveAttribute('aria-controls', 'app-navigation');
    await user.click(menu);
    expect(menu).toHaveAttribute('aria-expanded', 'true');
    await user.click(screen.getByRole('link', { name: 'Employees' }));
    expect(screen.getByTestId('current-page')).toHaveTextContent('/employees');
    expect(menu).toHaveAttribute('aria-expanded', 'false');
    await user.click(menu);
    screen.getByRole('link', { name: 'Devices' }).focus();
    await user.keyboard('{Escape}');
    expect(menu).toHaveAttribute('aria-expanded', 'false');
    expect(menu).toHaveFocus();
  });

  it('keeps administrator links out of the employee menu', () => {
    auth.role = 'EMPLOYEE';
    showLayout();
    expect(screen.queryByRole('link', { name: 'Employees' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Devices' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Device' })).toBeInTheDocument();
  });
});
