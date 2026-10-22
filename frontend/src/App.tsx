import { Outlet, Route, Routes } from 'react-router-dom';

import { RequireAuth } from './auth/RequireAuth';
import { RequireRole } from './auth/RequireRole';
import { Layout } from './components/Layout';
import { ChangePasswordPage } from './pages/ChangePasswordPage';
import { DashboardPage } from './pages/DashboardPage';
import { DevicePage } from './pages/DevicePage';
import { ForgotPasswordPage } from './pages/ForgotPasswordPage';
import { LoginPage } from './pages/LoginPage';
import { NotFoundPage } from './pages/NotFoundPage';
import { SetPasswordPage } from './pages/SetPasswordPage';
import { AttendancePage } from './pages/admin/AttendancePage';
import { AuditPage } from './pages/admin/AuditPage';
import { DevicesPage } from './pages/admin/DevicesPage';
import { EmployeesPage } from './pages/admin/EmployeesPage';

/**
 * Application routes.
 *
 * The guard only avoids showing a screen that would fail immediately; every
 * permission is enforced by the backend.
 */
export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/forgot-password" element={<ForgotPasswordPage />} />
      {/* Both flows share one page; the token decides which one it is. */}
      <Route path="/setup-password" element={<SetPasswordPage />} />
      <Route path="/reset-password" element={<SetPasswordPage />} />

      <Route
        element={
          <RequireAuth>
            <Layout>
              <Outlet />
            </Layout>
          </RequireAuth>
        }
      >
        <Route path="/" element={<DashboardPage />} />
        <Route path="/device" element={<DevicePage />} />
        <Route path="/change-password" element={<ChangePasswordPage />} />

        <Route
          path="/attendance"
          element={
            <RequireRole roles={['HR', 'ADMIN']}>
              <AttendancePage />
            </RequireRole>
          }
        />
        <Route
          path="/employees"
          element={
            <RequireRole roles={['HR', 'ADMIN']}>
              <EmployeesPage />
            </RequireRole>
          }
        />
        <Route
          path="/audit"
          element={
            <RequireRole roles={['HR', 'ADMIN']}>
              <AuditPage />
            </RequireRole>
          }
        />
        <Route
          path="/devices"
          element={
            <RequireRole roles={['ADMIN']}>
              <DevicesPage />
            </RequireRole>
          }
        />

        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}
