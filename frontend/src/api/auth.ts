import { apiRequest, resetCsrfBootstrap } from './client';
import type { CurrentUser } from './types';

/**
 * Session authentication.
 *
 * The identity always comes from the server session; the frontend never stores
 * or sends an employee id.
 */
export async function login(email: string, password: string): Promise<CurrentUser> {
  const user = await apiRequest<CurrentUser>('/api/v1/auth/login', {
    method: 'POST',
    body: { email, password },
  });
  // The session changed: the CSRF token has to be fetched again.
  resetCsrfBootstrap();
  return user;
}

export async function logout(): Promise<void> {
  await apiRequest<void>('/api/v1/auth/logout', { method: 'POST' });
  resetCsrfBootstrap();
}

export async function currentUser(): Promise<CurrentUser> {
  return apiRequest<CurrentUser>('/api/v1/auth/me');
}
