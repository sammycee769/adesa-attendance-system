import { Navigate, useLocation } from 'react-router-dom';
import type { ReactNode } from 'react';

import type { EmployeeRole } from '../api/types';
import { useAuth } from './AuthProvider';

/**
 * Route guard for the HR and administrator areas.
 *
 * Hiding a link is not an authorization mechanism: this guard only avoids
 * showing a screen that would immediately fail. The backend refuses every
 * request that the role is not allowed to make, so typing the URL never grants
 * access.
 */
export function RequireRole({ roles, children }: { roles: EmployeeRole[]; children: ReactNode }) {
  const { user, loading } = useAuth();
  const location = useLocation();

  if (loading) {
    return (
      <div className="page-status" role="status" aria-live="polite">
        Loading…
      </div>
    );
  }

  if (!user) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }

  if (!roles.includes(user.role)) {
    return (
      <div className="card" role="alert">
        <h1>Not available for your role</h1>
        <p className="hint">
          This area is reserved for {roles.map((role) => roleLabel(role)).join(' and ')} accounts. You are
          signed in as {user.fullName} ({user.employeeNumber}).
        </p>
      </div>
    );
  }

  return <>{children}</>;
}

/** The role as it is written in the interface. */
export function roleLabel(role: EmployeeRole): string {
  switch (role) {
    case 'ADMIN':
      return 'administrator';
    case 'HR':
      return 'HR';
    default:
      return 'employee';
  }
}
