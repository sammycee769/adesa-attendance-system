import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';

import { createEmployee, fetchEmployees, reactivateEmployee, resendEmployeeSetup, updateEmployee } from '../../api/admin';
import { ApiError } from '../../api/client';
import type { Employee, EmployeeRole } from '../../api/types';
import { useAuth } from '../../auth/AuthProvider';
import { ErrorBanner, SuccessBanner } from '../../components/Banners';
import { describeError } from '../../utils/errors';
import { formatDateTime } from '../../utils/format';
import { PASSWORD_HINT, validatePassword } from '../../utils/password';

/**
 * Employee accounts.
 *
 * HR may read the roster — they need it to review attendance — but cannot
 * create or change accounts: that stays with an administrator. The backend
 * enforces the same split, so the buttons below are a convenience, not the
 * security boundary.
 */

const ROLE_OPTIONS: { value: EmployeeRole; label: string }[] = [
  { value: 'EMPLOYEE', label: 'Employee' },
  { value: 'HR', label: 'HR' },
  { value: 'ADMIN', label: 'Administrator' },
];

export function EmployeesPage() {
  const navigate = useNavigate();
  const { user } = useAuth();
  const canManage = user?.role === 'ADMIN';

  const [employees, setEmployees] = useState<Employee[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<{ message: string; hint: string | null } | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  const [search, setSearch] = useState('');
  const [role, setRole] = useState<'' | EmployeeRole>('');
  const [active, setActive] = useState<'' | 'true' | 'false'>('');

  const [showForm, setShowForm] = useState(false);
  const [form, setForm] = useState({
    employeeNumber: '',
    fullName: '',
    email: '',
    password: '',
    role: 'EMPLOYEE' as EmployeeRole,
  });
  /** Returns keyboard focus to the "Add employee" button when the form closes. */
  const addButton = useRef<HTMLButtonElement | null>(null);

  const closeForm = () => {
    setShowForm(false);
    addButton.current?.focus();
  };

  const handleError = useCallback(
    (cause: unknown, fallback: string) => {
      if (cause instanceof ApiError && cause.isUnauthorized) {
        navigate('/login', { replace: true });
        return;
      }
      setError(describeError(cause, fallback));
    },
    [navigate],
  );

  const load = useCallback(async (filters: { search: string; role: string; active: string }) => {
    const result = await fetchEmployees({
      search: filters.search || undefined,
      role: filters.role === '' ? undefined : (filters.role as EmployeeRole),
      active: filters.active === '' ? undefined : filters.active === 'true',
    });
    setEmployees(result);
  }, []);

  useEffect(() => {
    let active2 = true;
    setLoading(true);
    (async () => {
      try {
        await load({ search, role, active });
        if (active2) {
          setError(null);
        }
      } catch (cause) {
        if (active2) {
          handleError(cause, 'The employee list could not be loaded.');
        }
      } finally {
        if (active2) {
          setLoading(false);
        }
      }
    })();
    return () => {
      active2 = false;
    };
  }, [search, role, active, load, handleError]);

  const handleCreate = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (busy) {
      return;
    }
    if (form.password && form.password.length > 0) {
      const weak = validatePassword(form.password);
      if (weak) {
        setError({ message: weak, hint: null });
        return;
      }
    }
    setBusy(true);
    setError(null);
    setSuccess(null);
    try {
      await createEmployee({
        employeeNumber: form.employeeNumber.trim(),
        fullName: form.fullName.trim(),
        email: form.email.trim(),
        // Empty means: e-mail a one-time setup link instead of setting a password here.
        password: form.password.length > 0 ? form.password : undefined,
        role: form.role,
      });
      setSuccess(
        form.password.length > 0
          ? `The account of ${form.fullName.trim()} was created. They must change the initial password at first sign-in.`
          : `The account of ${form.fullName.trim()} was created. A one-time account-setup link has been e-mailed to them.`,
      );
      setForm({ employeeNumber: '', fullName: '', email: '', password: '', role: 'EMPLOYEE' });
      setShowForm(false);
      await load({ search, role, active });
    } catch (cause) {
      handleError(cause, 'The account could not be created.');
    } finally {
      setBusy(false);
    }
  };

  /** Re-sends the account-setup link for an account that still needs a password. */
  const handleResendSetup = async (employee: Employee) => {
    if (busy) {
      return;
    }
    setBusy(true);
    setError(null);
    setSuccess(null);
    try {
      await resendEmployeeSetup(employee.id);
      setSuccess(`A new account-setup link was e-mailed to ${employee.fullName}.`);
      await load({ search, role, active });
    } catch (cause) {
      handleError(cause, 'The setup link could not be sent.');
    } finally {
      setBusy(false);
    }
  };

  const handleToggleActive = async (employee: Employee) => {
    if (busy) {
      return;
    }
    setBusy(true);
    setError(null);
    setSuccess(null);
    try {
      await updateEmployee(employee.id, { isActive: !employee.isActive });
      setSuccess(
        employee.isActive
          ? `The account of ${employee.fullName} was deactivated.`
          : `The account of ${employee.fullName} was activated.`,
      );
      await load({ search, role, active });
    } catch (cause) {
      handleError(cause, 'The account could not be changed.');
    } finally {
      setBusy(false);
    }
  };

  /**
   * Reactivates a deactivated account. Administrators can only be restored
   * through this dedicated action (the backend refuses to deactivate them), so
   * it is separated from the ordinary activate/deactivate toggle and always
   * asks for confirmation.
   */
  const handleReactivate = async (employee: Employee) => {
    if (busy) {
      return;
    }
    const confirmed = window.confirm(
      `Reactivate the account of ${employee.fullName}? They will be able to sign in again.`,
    );
    if (!confirmed) {
      return;
    }
    setBusy(true);
    setError(null);
    setSuccess(null);
    try {
      await reactivateEmployee(employee.id);
      setSuccess(`The account of ${employee.fullName} was reactivated.`);
      await load({ search, role, active });
    } catch (cause) {
      handleError(cause, 'The account could not be reactivated.');
    } finally {
      setBusy(false);
    }
  };

  const handleRoleChange = async (employee: Employee, nextRole: EmployeeRole) => {
    if (busy || nextRole === employee.role) {
      return;
    }
    setBusy(true);
    setError(null);
    setSuccess(null);
    try {
      await updateEmployee(employee.id, { role: nextRole });
      setSuccess(`The role of ${employee.fullName} is now ${nextRole}.`);
      await load({ search, role, active });
    } catch (cause) {
      handleError(cause, 'The role could not be changed.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="dashboard">
      <h1>Employees</h1>
      <p className="subtitle">
        {canManage
          ? 'Create accounts, change roles and deactivate employees. Every change is recorded in the audit trail.'
          : 'The employee roster. Managing accounts is reserved for administrators.'}
      </p>

      <ErrorBanner message={error?.message ?? null} hint={error?.hint ?? null} />
      <SuccessBanner message={success} />

      <section className="card">
        <div className="filter-grid">
          <div className="form-field">
            <label htmlFor="employee-search">Search</label>
            <input
              id="employee-search"
              name="employee-search"
              type="search"
              placeholder="Name, number or email"
              autoComplete="off"
              autoCapitalize="none"
              autoCorrect="off"
              spellCheck={false}
              value={search}
              onChange={(event) => setSearch(event.target.value)}
            />
          </div>
          <div className="form-field">
            <label htmlFor="employee-role">Role</label>
            <select
              id="employee-role"
              name="employee-role"
              value={role}
              onChange={(event) => setRole(event.target.value as '' | EmployeeRole)}
            >
              <option value="">All roles</option>
              {ROLE_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </div>
          <div className="form-field">
            <label htmlFor="employee-active">Status</label>
            <select
              id="employee-active"
              name="employee-active"
              value={active}
              onChange={(event) => setActive(event.target.value as '' | 'true' | 'false')}
            >
              <option value="">All</option>
              <option value="true">Active</option>
              <option value="false">Deactivated</option>
            </select>
          </div>
        </div>
      </section>

      <section className="card" aria-labelledby="employee-list-heading">
        <div className="section-header">
          <h2 id="employee-list-heading">Roster</h2>
          {canManage ? (
            <button
              ref={addButton}
              type="button"
              className="button button-primary"
              onClick={() => (showForm ? closeForm() : setShowForm(true))}
              aria-expanded={showForm}
            >
              {showForm ? 'Cancel' : 'Add employee'}
            </button>
          ) : null}
        </div>

        {showForm && canManage ? (
          <form onSubmit={handleCreate} noValidate>
            <div className="filter-grid">
              <div className="form-field">
                <label htmlFor="new-number">Employee number</label>
                <input
                  id="new-number"
                  name="new-number"
                  type="text"
                  maxLength={32}
                  autoFocus
                  autoComplete="off"
                  autoCapitalize="characters"
                  autoCorrect="off"
                  spellCheck={false}
                  required
                  value={form.employeeNumber}
                  onChange={(event) => setForm({ ...form, employeeNumber: event.target.value })}
                  disabled={busy}
                />
              </div>
              <div className="form-field">
                <label htmlFor="new-name">Full name</label>
                <input
                  id="new-name"
                  name="new-name"
                  type="text"
                  maxLength={200}
                  required
                  value={form.fullName}
                  onChange={(event) => setForm({ ...form, fullName: event.target.value })}
                  disabled={busy}
                />
              </div>
              <div className="form-field">
                <label htmlFor="new-email">Email</label>
                <input
                  id="new-email"
                  name="new-email"
                  type="email"
                  maxLength={320}
                  autoComplete="email"
                  autoCapitalize="none"
                  autoCorrect="off"
                  spellCheck={false}
                  required
                  value={form.email}
                  onChange={(event) => setForm({ ...form, email: event.target.value })}
                  disabled={busy}
                />
              </div>
              <div className="form-field">
                <label htmlFor="new-password">Initial password (optional)</label>
                <input
                  id="new-password"
                  name="new-password"
                  type="password"
                  minLength={12}
                  autoComplete="new-password"
                  value={form.password}
                  onChange={(event) => setForm({ ...form, password: event.target.value })}
                  disabled={busy}
                />
                <p className="hint">
                  Leave blank to e-mail the employee a one-time account-setup link so they choose
                  their own password. {PASSWORD_HINT}
                </p>
              </div>
              <div className="form-field">
                <label htmlFor="new-role">Role</label>
                <select
                  id="new-role"
                  name="new-role"
                  value={form.role}
                  onChange={(event) => setForm({ ...form, role: event.target.value as EmployeeRole })}
                  disabled={busy}
                >
                  {ROLE_OPTIONS.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </select>
              </div>
            </div>
            <button type="submit" className="button button-primary" disabled={busy}>
              {busy ? 'Creating…' : 'Create the account'}
            </button>
          </form>
        ) : null}

        {loading ? (
          <p className="page-status" role="status" aria-live="polite">
            Loading the roster…
          </p>
        ) : employees.length === 0 ? (
          <p className="hint">No employee matches these filters.</p>
        ) : (
          <div className="table-scroll">
            <table>
              <caption className="visually-hidden">Employee accounts</caption>
              <thead>
                <tr>
                  <th scope="col">Employee</th>
                  <th scope="col">Email</th>
                  <th scope="col">Role</th>
                  <th scope="col">Status</th>
                  <th scope="col">Since</th>
                  {canManage ? <th scope="col">Actions</th> : null}
                </tr>
              </thead>
              <tbody>
                {employees.map((employee) => (
                  <tr key={employee.id}>
                    <td>
                      {employee.fullName}
                      <span className="cell-note"> {employee.employeeNumber}</span>
                    </td>
                    <td>{employee.email}</td>
                    <td>{employee.role}</td>
                    <td>
                      <span
                        className={`badge ${employee.isActive ? 'badge-signed-in' : 'badge-not-signed-in'}`}
                      >
                        {employee.isActive ? 'Active' : 'Deactivated'}
                      </span>
                      {employee.isActive && employee.mustChangePassword ? (
                        <span className="badge badge-not-signed-in"> Setup pending</span>
                      ) : null}
                    </td>
                    <td>{formatDateTime(employee.createdAt)}</td>
                    {canManage ? (
                      <td>
                        <div className="button-row">
                          {employee.role === 'ADMIN' ? (
                            employee.isActive ? (
                              <span className="hint">Administrators cannot be deactivated.</span>
                            ) : (
                              <button
                                type="button"
                                className="button button-primary"
                                disabled={busy}
                                onClick={() => handleReactivate(employee)}
                              >
                                Reactivate
                              </button>
                            )
                          ) : (
                            <button
                              type="button"
                              className="button button-secondary"
                              disabled={busy}
                              onClick={() => handleToggleActive(employee)}
                            >
                              {employee.isActive ? 'Deactivate' : 'Activate'}
                            </button>
                          )}
                          <select
                            aria-label={`Role of ${employee.fullName}`}
                            value={employee.role}
                            disabled={busy}
                            onChange={(event) =>
                              handleRoleChange(employee, event.target.value as EmployeeRole)
                            }
                          >
                            {ROLE_OPTIONS.map((option) => (
                              <option key={option.value} value={option.value}>
                                {option.label}
                              </option>
                            ))}
                          </select>
                          {employee.isActive && employee.mustChangePassword ? (
                            <button
                              type="button"
                              className="button button-secondary"
                              disabled={busy}
                              onClick={() => handleResendSetup(employee)}
                            >
                              Re-send setup link
                            </button>
                          ) : null}
                        </div>
                      </td>
                    ) : null}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}
