import { useRef, useState, type ReactNode } from 'react';
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
  const [navigationOpen, setNavigationOpen] = useState(false);
  const menuButton = useRef<HTMLButtonElement>(null);

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
      <header className="app-header" onKeyDown={event => {
        if (event.key === 'Escape' && navigationOpen) {
          setNavigationOpen(false);
          menuButton.current?.focus();
        }
      }}>
        <div className="app-header-inner">
          <Link to="/" className="app-title" onClick={() => setNavigationOpen(false)}>
            <img src={navLogo} alt="ADESA HQ Attendance" className="app-title-logo" />
          </Link>

          <button ref={menuButton} type="button" className="app-menu-toggle"
            aria-expanded={navigationOpen} aria-controls="app-navigation"
            onClick={() => setNavigationOpen(open => !open)}>
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor"
              strokeWidth="2" strokeLinecap="round" aria-hidden="true" focusable="false">
              <path d={navigationOpen ? 'M6 6l12 12M6 18L18 6' : 'M4 6h16M4 12h16M4 18h16'} />
            </svg>
            Menu
          </button>

          <div id="app-navigation" className={`app-navigation${navigationOpen ? ' is-open' : ''}`}>
            <nav className="app-nav" aria-label="Main" onClick={event => {
              if (event.target instanceof Element && event.target.closest('a')) setNavigationOpen(false);
            }}>
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
        </div>
      </header>

      <main className="app-main">{children}</main>

      <footer className="app-footer">
        <p>Attendance can only be recorded from the approved ADESA office network.</p>
      </footer>
    </div>
  );
}
