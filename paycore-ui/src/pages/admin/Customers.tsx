import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { api, errorMessage } from '../../api/client';
import type { Account, AccountEvent, CustomerPage, CustomerRoles, CustomerSummary, RoleEvent } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { ScreenTitle } from '../../components/Layout';
import { Card, formatAccountNumber, formatDate, Notice, StatusBadge } from '../../components/ui';
import { markDone, useJourney } from '../../journey';
import { AccountMoney } from './AccountMoney';
import { FinishCard } from './FinishCard';

const ROLES = ['CUSTOMER', 'SUPPORT', 'ADMIN', 'OPERATIONS'];

export function Customers() {
  const { customerEmail } = useJourney();
  const [search, setSearch] = useState('');
  const [page, setPage] = useState<CustomerPage | null>(null);
  const [selected, setSelected] = useState<CustomerSummary | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async (term: string) => {
    try {
      setPage(await api<CustomerPage>(`/api/v1/admin/customers?size=50&search=${encodeURIComponent(term)}`));
      setError(null);
    } catch (e) {
      setError(errorMessage(e));
    }
  }, []);

  useEffect(() => {
    void load('');
  }, [load]);

  function submit(e: FormEvent) {
    e.preventDefault();
    void load(search);
  }

  async function refresh() {
    await load(search);
  }

  return (
    <>
      <ScreenTitle title="Customers">
        Admins manage roles and account status, each change with a reason in an append-only history. Operations
        officers see balances and statements and can request adjustments; nobody can move money on their own.
      </ScreenTitle>

      {error && <Notice tone="bad">{error}</Notice>}

      <div className="grid-master">
        <Card
          title="Customers"
          aside={
            <form onSubmit={submit} className="search">
              <input type="search" placeholder="Search name or email" value={search} onChange={(e) => setSearch(e.target.value)} aria-label="Search customers" />
            </form>
          }
        >
          {!page ? (
            <p className="muted">Loading…</p>
          ) : (
            <ul className="people">
              {page.customers.map((c) => (
                <li key={c.id}>
                  <button className={selected?.id === c.id ? 'selected' : ''} onClick={() => setSelected(c)}>
                    <span>
                      <strong>
                        {c.firstName} {c.lastName}
                      </strong>
                      {c.email === customerEmail && <span className="chip">You</span>}
                      <span className="muted small block">{c.email}</span>
                    </span>
                    <span className="people-side">
                      {c.roles.map((r) => (
                        <span key={r} className={`chip ${r === 'CUSTOMER' ? 'chip-soft' : ''}`}>
                          {r}
                        </span>
                      ))}
                      <StatusBadge status={c.status} />
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
          {page && page.page.totalElements > page.customers.length && (
            <p className="muted small">
              Showing {page.customers.length} of {page.page.totalElements}. Search to narrow it down.
            </p>
          )}
        </Card>

        {selected ? (
          <CustomerDetail key={selected.id} customer={selected} onChanged={refresh} />
        ) : (
          <Card title="Select a customer">
            <p className="muted">
              Pick someone to manage their roles and see their audit history. The account marked <span className="chip">You</span> is the
              one you registered.
            </p>
          </Card>
        )}
      </div>

      <FinishCard />
    </>
  );
}

function CustomerDetail({ customer, onChanged }: { customer: CustomerSummary; onChanged: () => Promise<void> }) {
  const { session, can } = useAuth();
  const manager = can('ROLE_MANAGE');
  const [roles, setRoles] = useState<string[]>(customer.roles);
  const [history, setHistory] = useState<RoleEvent[]>([]);
  const [status, setStatus] = useState(customer.status);
  const [role, setRole] = useState('SUPPORT');
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const isSelf = session?.customerId === customer.id;
  const base = `/api/v1/admin/customers/${customer.id}/roles`;

  const loadHistory = useCallback(async () => {
    try {
      setHistory(await api<RoleEvent[]>(`${base}/history`));
    } catch (e) {
      setError(errorMessage(e));
    }
  }, [base]);

  useEffect(() => {
    if (manager) void loadHistory();
  }, [loadHistory, manager]);

  async function run(label: string, action: () => Promise<void>) {
    setBusy(true);
    setError(null);
    setDone(null);
    try {
      await action();
      setDone(label);
      await Promise.all([loadHistory(), onChanged()]);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  const assign = () =>
    run(`Assigned ${role}.`, async () => {
      const r = await api<CustomerRoles>(base, { body: { role, reason } });
      setRoles(r.roles);
      setReason('');
    });

  const revoke = (name: string) =>
    run(`Revoked ${name}.`, async () => {
      const r = await api<CustomerRoles>(`${base}/${name}/revoke`, { body: { reason } });
      setRoles(r.roles);
      setReason('');
    });

  const changeStatus = (action: 'suspend' | 'reactivate') =>
    run(action === 'suspend' ? 'Customer suspended.' : 'Customer reactivated.', async () => {
      const c = await api<{ status: CustomerSummary['status'] }>(`/api/v1/customers/${customer.id}/${action}`, { method: 'POST' });
      setStatus(c.status);
    });

  return (
    <Card
      title={
        <>
          {customer.firstName} {customer.lastName}
        </>
      }
      aside={<StatusBadge status={status} />}
    >
      <p className="muted small">
        {customer.email} · joined {formatDate(customer.createdAt)}
        <br />
        <code>{customer.id}</code>
      </p>

      {manager && (
        <>
          {isSelf && <Notice tone="warn">This is you. Admins can't change their own roles, so the API will answer 403 here.</Notice>}

          <h3>Roles</h3>
          <div className="role-row">
            {roles.map((r) => (
              <span key={r} className="chip chip-action">
                {r}
                <button title={`Revoke ${r}`} aria-label={`Revoke ${r}`} disabled={busy || !reason.trim()} onClick={() => revoke(r)}>
                  ×
                </button>
              </span>
            ))}
          </div>

          <div className="form">
            <label className="field">
              <span className="field-label">Reason (required, stored in the audit trail)</span>
              <input value={reason} maxLength={500} onChange={(e) => setReason(e.target.value)} placeholder="e.g. Joining the support rota" />
            </label>
            <div className="actions">
              <select value={role} onChange={(e) => setRole(e.target.value)} aria-label="Role to assign">
                {ROLES.map((r) => (
                  <option key={r}>{r}</option>
                ))}
              </select>
              <button className="btn btn-primary" disabled={busy || !reason.trim()} onClick={assign}>
                Assign role
              </button>
            </div>
            <p className="muted small">To revoke a role, type a reason and then click × on it.</p>
          </div>

          <h3>Account status</h3>
          <div className="actions">
            <button className="btn btn-ghost" disabled={busy} onClick={() => changeStatus('suspend')}>
              Suspend
            </button>
            <button className="btn btn-ghost" disabled={busy} onClick={() => changeStatus('reactivate')}>
              Reactivate
            </button>
          </div>
          <p className="muted small">
            Only <em>active</em> customers can be suspended. New customers are still pending email verification, so expect a{' '}
            <code>409</code> from the domain rules, and a suspended customer can no longer sign in.
          </p>
        </>
      )}

      {done && <Notice tone="good">{done}</Notice>}
      {error && <Notice tone="bad">{error}</Notice>}

      <AccountsPanel customerId={customer.id} />

      {manager && <h3>Role history</h3>}
      <ol className="timeline">
        {history
          .slice()
          .reverse()
          .map((e) => (
            <li key={e.id} className={e.action === 'REVOKED' ? 'revoked' : ''}>
              <strong>
                {e.action === 'ASSIGNED' ? '+' : '−'} {e.role}
              </strong>{' '}
              <span className="muted small">
                {formatDate(e.occurredAt)} · by {e.performedBy ? (e.performedBy === session?.customerId ? 'you' : <code>{e.performedBy.slice(0, 8)}…</code>) : 'system'}
              </span>
              {e.reason && <div className="small">“{e.reason}”</div>}
            </li>
          ))}
      </ol>
    </Card>
  );
}

type AccountAction = 'activate' | 'freeze' | 'unfreeze' | 'close';

/** Which staff actions the account's current status allows. */
const ACTIONS: Record<Account['status'], AccountAction[]> = {
  PENDING: ['activate', 'close'],
  ACTIVE: ['freeze', 'close'],
  FROZEN: ['unfreeze', 'close'],
  CLOSED: [],
};

const ACTION_LABELS: Record<AccountAction, string> = {
  activate: 'Activate',
  freeze: 'Freeze',
  unfreeze: 'Unfreeze',
  close: 'Close',
};

function AccountsPanel({ customerId }: { customerId: string }) {
  const { can } = useAuth();
  const manager = can('ACCOUNT_MANAGE');
  const [accounts, setAccounts] = useState<Account[] | null>(null);
  const [openId, setOpenId] = useState<string | null>(null);
  const [moneyId, setMoneyId] = useState<string | null>(null);
  const [history, setHistory] = useState<AccountEvent[]>([]);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setAccounts(await api<Account[]>(`/api/v1/admin/customers/${customerId}/accounts`));
    } catch (e) {
      setError(errorMessage(e));
    }
  }, [customerId]);

  const loadHistory = useCallback(async (accountId: string) => {
    setHistory(await api<AccountEvent[]>(`/api/v1/admin/accounts/${accountId}/history`));
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  async function toggleHistory(accountId: string) {
    if (openId === accountId) {
      setOpenId(null);
      return;
    }
    setOpenId(accountId);
    try {
      await loadHistory(accountId);
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  async function act(account: Account, action: AccountAction) {
    setBusy(true);
    setError(null);
    setDone(null);
    try {
      const updated = await api<Account>(`/api/v1/admin/accounts/${account.id}/${action}`, { body: { reason } });
      setDone(`${formatAccountNumber(updated.accountNumber)} is now ${updated.status.toLowerCase()}.`);
      setReason('');
      if (action === 'activate') markDone('activate');
      await load();
      if (openId === account.id) await loadHistory(account.id);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <h3>Accounts</h3>
      {accounts === null ? (
        <p className="muted small">Loading…</p>
      ) : accounts.length === 0 ? (
        <p className="muted small">No accounts yet. The customer opens one from their app.</p>
      ) : (
        <>
          {manager && (
            <label className="field">
              <span className="field-label">Reason (required, stored in the account history)</span>
              <input value={reason} maxLength={500} onChange={(e) => setReason(e.target.value)} placeholder="e.g. KYC verified" />
            </label>
          )}
          <ul className="account-list">
            {accounts.map((a) => (
              <li key={a.id}>
                <div className="account-row">
                  <span>
                    <code>{formatAccountNumber(a.accountNumber)}</code>{' '}
                    <span className="muted small">
                      {a.type.toLowerCase()} · {a.currency}
                    </span>
                  </span>
                  <span className="people-side">
                    <StatusBadge status={a.status} />
                  </span>
                </div>
                <div className="actions">
                  {(manager ? ACTIONS[a.status] : []).map((action) => (
                    <button
                      key={action}
                      className={`btn ${action === 'activate' ? 'btn-primary' : 'btn-ghost'}`}
                      disabled={busy || !reason.trim()}
                      onClick={() => act(a, action)}
                    >
                      {ACTION_LABELS[action]}
                    </button>
                  ))}
                  <button className="btn btn-ghost" onClick={() => toggleHistory(a.id)}>
                    {openId === a.id ? 'Hide history' : 'History'}
                  </button>
                  {a.status !== 'PENDING' && (
                    <button className="btn btn-ghost" onClick={() => setMoneyId(moneyId === a.id ? null : a.id)}>
                      {moneyId === a.id ? 'Hide money' : 'Balance & statement'}
                    </button>
                  )}
                </div>
                {moneyId === a.id && <AccountMoney account={a} />}
                {openId === a.id && (
                  <ol className="timeline">
                    {history
                      .slice()
                      .reverse()
                      .map((e) => (
                        <li key={e.id} className={e.toStatus === 'CLOSED' || e.toStatus === 'FROZEN' ? 'revoked' : ''}>
                          <strong>{e.eventType}</strong>{' '}
                          <span className="muted small">
                            {e.fromStatus ?? '—'} → {e.toStatus} · {formatDate(e.occurredAt)}
                          </span>
                          {e.reason && <div className="small">“{e.reason}”</div>}
                        </li>
                      ))}
                  </ol>
                )}
              </li>
            ))}
          </ul>
          <p className="muted small">
            Activation is refused with <code>403 KYC_VERIFICATION_REQUIRED</code> until the customer's KYC is verified.
          </p>
        </>
      )}
      {done && <Notice tone="good">{done}</Notice>}
      {error && <Notice tone="bad">{error}</Notice>}
    </>
  );
}
