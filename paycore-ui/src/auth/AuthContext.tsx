import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import {
  api,
  decodeClaims,
  getSession,
  onSessionChange,
  sessionFromLogin,
  setSession,
  type AccessClaims,
  type Session,
} from '../api/client';
import type { Customer, LoginResponse } from '../api/types';

interface AuthState {
  session: Session | null;
  claims: AccessClaims | null;
  profile: Customer | null;
  isAdmin: boolean;
  login: (email: string, password: string) => Promise<LoginResponse>;
  logout: (redirectTo?: string) => Promise<void>;
  reloadProfile: () => Promise<void>;
}

const AuthContext = createContext<AuthState | null>(null);

// Where a deliberate sign-out should land; read by RequireAuth.
let signOutRedirect: string | null = null;

export function getSignOutRedirect() {
  return signOutRedirect;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSessionState] = useState<Session | null>(getSession);
  const [profile, setProfile] = useState<Customer | null>(null);

  useEffect(() => onSessionChange(setSessionState), []);

  const claims = useMemo(() => (session ? decodeClaims(session.accessToken) : null), [session]);

  const reloadProfile = useCallback(async () => {
    if (!getSession()) {
      setProfile(null);
      return;
    }
    try {
      setProfile(await api<Customer>('/api/v1/customers/me'));
    } catch {
      setProfile(null);
    }
  }, []);

  useEffect(() => {
    if (session?.customerId) void reloadProfile();
    else setProfile(null);
  }, [session?.customerId, reloadProfile]);

  const login = useCallback(async (email: string, password: string) => {
    const response = await api<LoginResponse>('/api/v1/auth/login', {
      body: { email, password },
      auth: 'none',
    });
    signOutRedirect = null;
    setSession(sessionFromLogin(response));
    return response;
  }, []);

  /**
   * Forgets the tokens locally at once, then revokes the LoginSession and
   * its refresh tokens server-side. Clearing the session makes a protected
   * page redirect, so {@code redirectTo} tells RequireAuth where to send
   * the visitor instead of the generic sign-in page.
   */
  const logout = useCallback(async (redirectTo?: string) => {
    const current = getSession();
    signOutRedirect = redirectTo ?? null;
    setSession(null);
    if (current) {
      try {
        await api<void>('/api/v1/auth/logout', {
          method: 'POST',
          auth: 'none',
          headers: { 'X-Session-Token': current.sessionToken },
        });
      } catch {
        // Already signed out locally; nothing else to do if the API is down.
      }
    }
  }, []);

  const value = useMemo<AuthState>(
    () => ({
      session,
      claims,
      profile,
      isAdmin: claims?.roles.includes('ADMIN') ?? false,
      login,
      logout,
      reloadProfile,
    }),
    [session, claims, profile, login, logout, reloadProfile],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth must be used inside AuthProvider');
  return context;
}
