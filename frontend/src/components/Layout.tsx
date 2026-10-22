import { useState, type ReactNode } from 'react';
import { Link, NavLink } from 'react-router-dom';

import { useAuth } from '../auth/AuthProvider';
import navLogo from '../assets/navbar-logo.jpeg';

/**
 * Application frame: title, navigation, the signed-in identity and the logout
 * action.
 */
export function Layout({ children }: { children: ReactNode }) {
  const { user, signOut } = useAuth();
  const [signingOut, setSigningOut] = useState(false);

  const handleLogout = async () => {
    setSigningOut(true);
    try {
      await signOut();
    } finally {
      setSigningOut(false);
    }
  };

  return (
    <div className="app-shell">
      <header className="app-header">
        <div className="app-header-inner">
          <Link to="/" className="app-title">
            <img src={navLogo} alt="ADESA HQ Attendance" className="app-title-logo" />
          </Link>

          <nav className="app-nav" aria-label="Main">
            <NavLink to="/" end>
              Dashboard
            </NavLink>
            <NavLink to="/device">Device</NavLink>
            {user && user.role !== 'EMPLOYEE' ? (
              <>
                <NavLink to="/attendance">Attendance</NavLink>
                <NavLink to="/employees">Employees</NavLink>
                <NavLink to="/audit">Audit</NavLink>
              </>
            ) : null}
            {user && user.role === 'ADMIN' ? <NavLink to="/devices">Devices</NavLink> : null}
            <NavLink to="/change-password">Password</NavLink>
          </nav>

          <div className="app-identity">
            {user ? (
              <span className="app-user">
                Signed in as {user.fullName} ({user.employeeNumber})
              </span>
            ) : null}
            <button type="button" className="button button-secondary" onClick={handleLogout} disabled={signingOut}>
              {signingOut ? 'Signing out…' : 'Log out'}
            </button>
          </div>
        </div>
      </header>

      <main className="app-main">{children}</main>

      <footer className="app-footer">
        <p>Attendance can only be recorded from the approved ADESA office network.</p>
      </footer>
    </div>
  );
}
