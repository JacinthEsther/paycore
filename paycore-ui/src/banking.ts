import { useCallback, useEffect, useState } from 'react';
import { api, ApiError, errorMessage } from './api/client';
import type { Account, Balance, KycStatus, Statement } from './api/types';

// What every banking screen needs: the customer's naira account, its
// balance and their KYC status. Screens that move money call refresh()
// afterwards; every mounted screen then reloads.

const listeners = new Set<() => void>();

/** Ask every screen showing the account to reload it (after money moved). */
export function refreshBanking() {
  listeners.forEach((listener) => listener());
}

export interface Banking {
  loading: boolean;
  error: string | null;
  /** The customer's naira account (the first one that is not closed), if any. */
  account: Account | null;
  /** Null until the account is active. */
  balance: Balance | null;
  kycStatus: KycStatus | 'NONE' | null;
  reload: () => Promise<void>;
}

export function useBanking(): Banking {
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [account, setAccount] = useState<Account | null>(null);
  const [balance, setBalance] = useState<Balance | null>(null);
  const [kycStatus, setKycStatus] = useState<KycStatus | 'NONE' | null>(null);

  const reload = useCallback(async () => {
    try {
      const [accounts, kyc] = await Promise.all([
        api<Account[]>('/api/v1/accounts'),
        api<{ status: KycStatus }>('/api/v1/kyc/status').catch((e) =>
          e instanceof ApiError && e.status === 404 ? { status: 'NONE' as const } : null,
        ),
      ]);

      const primary = accounts.find((a) => a.currency === 'NGN' && a.status !== 'CLOSED') ?? null;
      setAccount(primary);
      setKycStatus(kyc?.status ?? null);
      setBalance(
        primary && primary.status !== 'PENDING'
          ? await api<Balance>(`/api/v1/ledger/accounts/${primary.id}/balance`)
          : null,
      );
      setError(null);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void reload();
    listeners.add(reload);
    return () => {
      listeners.delete(reload);
    };
  }, [reload]);

  return { loading, error, account, balance, kycStatus, reload };
}

/** The account's statement for the month so far (or a date range). */
export function useStatement(accountId: string | null | undefined, query = '', refreshKey = 0) {
  const [statement, setStatement] = useState<Statement | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!accountId) return;
    let cancelled = false;
    const load = () =>
      api<Statement>(`/api/v1/ledger/accounts/${accountId}/statement${query}`)
        .then((s) => !cancelled && setStatement(s))
        .catch((e) => !cancelled && setError(errorMessage(e)));
    void load();
    listeners.add(load);
    return () => {
      cancelled = true;
      listeners.delete(load);
    };
  }, [accountId, query, refreshKey]);

  return { statement, error };
}
