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
import { staffRoleOf } from '../staff';

interface AuthState {
  session: Session | null;
  claims: AccessClaims | null;
  profile: Customer | null;
  isStaff: boolean;
  isCustomer: boolean;
  can: (permission: string) => boolean;
  login: (email: string, password: string) => Promise<LoginResponse>;
  logout: (redirectTo?: string) => Promise<void>;
  reloadProfile: () => Promise<void>;
}

const AuthContext = createContext<AuthState | null>(null);

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
      }
    }
  }, []);

  const value = useMemo<AuthState>(
    () => ({
      session,
      claims,
      profile,
      isStaff: staffRoleOf(claims?.roles) !== null,
      isCustomer: claims?.roles.includes('CUSTOMER') ?? false,
      can: (permission: string) => claims?.permissions.includes(permission) ?? false,
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
