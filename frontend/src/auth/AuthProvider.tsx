import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';

import { currentUser, login as apiLogin, logout as apiLogout } from '../api/auth';
import type { CurrentUser, DeviceStatus } from '../api/types';

interface AuthContextValue {
  /** The signed-in employee, or {@code null} when there is no session. */
  user: CurrentUser | null;
  /** True while the session is being restored on page load. */
  loading: boolean;
  signIn: (email: string, password: string) => Promise<void>;
  signOut: () => Promise<void>;
  /** Reloads the identity from the server. */
  refresh: () => Promise<void>;
  /** Updates the device state shown in the header after a device change. */
  applyDevice: (device: DeviceStatus) => void;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [loading, setLoading] = useState(true);

  const refresh = useCallback(async () => {
    try {
      setUser(await currentUser());
    } catch {
      // A missing or expired session simply means "signed out".
      setUser(null);
    }
  }, []);

  useEffect(() => {
    let active = true;
    (async () => {
      try {
        const me = await currentUser();
        if (active) {
          setUser(me);
        }
      } catch {
        if (active) {
          setUser(null);
        }
      } finally {
        if (active) {
          setLoading(false);
        }
      }
    })();
    return () => {
      active = false;
    };
  }, []);

  const signIn = useCallback(async (email: string, password: string) => {
    const me = await apiLogin(email, password);
    setUser(me);
  }, []);

  const signOut = useCallback(async () => {
    try {
      await apiLogout();
    } finally {
      // The local state is cleared even when the server call failed: the user
      // asked to leave the application.
      setUser(null);
    }
  }, []);

  const applyDevice = useCallback((device: DeviceStatus) => {
    setUser((current) => (current ? { ...current, device } : current));
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({ user, loading, signIn, signOut, refresh, applyDevice }),
    [user, loading, signIn, signOut, refresh, applyDevice],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used inside an AuthProvider');
  }
  return context;
}
