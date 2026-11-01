import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { refreshSession, setAccessToken, setSessionExpiredHandler } from '../api/client.js';
import { authApi, setupApi } from '../api/endpoints.js';
import { hasAnyRole } from './roles.js';

const AuthContext = createContext(null);

/**
 * Holds the signed-in user. On first load it checks whether first-run setup is needed,
 * then tries a silent refresh (httpOnly cookie) so a page reload keeps the session.
 */
export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [initializing, setInitializing] = useState(true);
  const [setupRequired, setSetupRequired] = useState(false);
  const [sessionExpired, setSessionExpired] = useState(false);

  useEffect(() => {
    setSessionExpiredHandler(() => {
      setUser(null);
      setSessionExpired(true);
    });
    let cancelled = false;
    (async () => {
      try {
        const status = await setupApi.status();
        if (cancelled) return;
        if (status.setupRequired) {
          setSetupRequired(true);
          return;
        }
        try {
          const session = await refreshSession();
          if (!cancelled) setUser(session.user);
        } catch {
          // No valid session: user must sign in.
        }
      } catch {
        // Backend unreachable: the login page will show the error on submit.
      } finally {
        if (!cancelled) setInitializing(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const login = useCallback(async (username, password) => {
    const res = await authApi.login(username, password);
    setAccessToken(res.accessToken);
    setSessionExpired(false);
    setUser(res.user);
    return res.user;
  }, []);

  const logout = useCallback(async () => {
    try {
      await authApi.logout();
    } finally {
      setAccessToken(null);
      setUser(null);
    }
  }, []);

  const completeSetup = useCallback(() => setSetupRequired(false), []);

  const value = useMemo(
    () => ({
      user,
      initializing,
      setupRequired,
      sessionExpired,
      login,
      logout,
      completeSetup,
      can: (roles) => hasAnyRole(user, roles),
    }),
    [user, initializing, setupRequired, sessionExpired, login, logout, completeSetup],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>');
  return ctx;
}

// Exported for tests that need to supply a fixed auth state.
export { AuthContext };
