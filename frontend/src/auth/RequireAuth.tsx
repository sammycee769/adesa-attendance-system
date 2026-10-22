import { Navigate, useLocation } from 'react-router-dom';
import type { ReactNode } from 'react';

import { useAuth } from './AuthProvider';

/**
 * Route guard for the signed-in area.
 *
 * Hiding a link is not an authorization mechanism: this guard only avoids
 * showing a screen that would immediately fail. The backend enforces every
 * permission itself, so changing the URL never grants access.
 *
 * <p>It also sends an account that still has to choose a password to the
 * change-password screen. This is a convenience only — the backend refuses
 * every other request for such an account, so the restriction holds even if
 * the URL is typed directly.
 */
export function RequireAuth({ children }: { children: ReactNode }) {
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

  if (user.mustChangePassword && location.pathname !== '/change-password') {
    return <Navigate to="/change-password" replace />;
  }

  return <>{children}</>;
}
