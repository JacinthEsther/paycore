import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api, errorMessage } from '../../api/client';
import type { Account, AccountEvent, Customer, CustomerPage, CustomerRoles, CustomerSummary, RoleEvent } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { ScreenTitle } from '../../components/Layout';
import { Card, formatAccountNumber, formatDate, Notice, StatusBadge } from '../../components/ui';
import { markDone, useJourney } from '../../journey';
import { ASSIGNABLE_ROLES, roleLabel, staffRoleOf } from '../../staff';
import { AccountMoney } from './AccountMoney';
import { FinishCard } from './FinishCard';


export function Customers() {
  const { customerEmail } = useJourney();
  const [params] = useSearchParams();
  const initial = params.get('q') ?? '';
  const [search, setSearch] = useState(initial);
  const [page, setPage] = useState<CustomerPage | null>(null);
  const [selected, setSelected] = useState<CustomerSummary | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async (term: string) => {
    try {
      const found = await api<CustomerPage>(`/api/v1/admin/customers?size=50&search=${encodeURIComponent(term)}`);
      setPage(found);
      setError(null);
      return found;
    } catch (e) {
      setError(errorMessage(e));
      return null;
    }
  }, []);

  useEffect(() => {
    void load(initial).then((found) => {
      if (initial && found?.customers.length === 1) setSelected(found.customers[0]);
    });
  }, [load, initial]);

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
        Look anyone up. What you can change depends on your desk.
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
                          {roleLabel(r)}
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
              Pick someone to see what your desk can do for them. The account marked <span className="chip">You</span> is the
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
  const isStaffMember = staffRoleOf(roles) !== null;
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
      await Promise.all([manager ? loadHistory() : Promise.resolve(), onChanged()]);
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

      <ContactDetails customerId={customer.id} isStaffMember={isStaffMember} onChanged={onChanged} />

      {can('CUSTOMER_SUSPEND') && !isStaffMember && (
        <>
          <h3>Customer status</h3>
          <div className="actions">
            <button className="btn btn-ghost" disabled={busy || status !== 'ACTIVE'} onClick={() => changeStatus('suspend')}>
              Suspend
            </button>
            <button className="btn btn-ghost" disabled={busy || status !== 'SUSPENDED'} onClick={() => changeStatus('reactivate')}>
              Reactivate
            </button>
          </div>
          <p className="muted small">A suspended customer cannot sign in until Compliance reactivates them.</p>
        </>
      )}

      {manager && (
        <>
          {isSelf && <Notice tone="warn">This is you. Nobody may change their own roles, so the API will answer 403 here.</Notice>}

          <h3>Roles</h3>
          <div className="role-row">
            {roles.map((r) => (
              <span key={r} className="chip chip-action">
                {roleLabel(r)}
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
                {ASSIGNABLE_ROLES.map((r) => (
                  <option key={r} value={r}>
                    {roleLabel(r)}
                  </option>
                ))}
              </select>
              <button className="btn btn-primary" disabled={busy || !reason.trim()} onClick={assign}>
                Assign role
              </button>
            </div>
            <p className="muted small">
              To revoke a role, type a reason and then click × on it. Some roles cannot be held together: an Operations
              Officer cannot also be a Supervisor, and the System Admin holds no other staff role.
            </p>
          </div>
        </>
      )}

      {done && <Notice tone="good">{done}</Notice>}
      {error && <Notice tone="bad">{error}</Notice>}

      {can('ACCOUNT_VIEW_ALL') && <AccountsPanel customerId={customer.id} />}

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

function ContactDetails({
  customerId,
  isStaffMember,
  onChanged,
}: {
  customerId: string;
  isStaffMember: boolean;
  onChanged: () => Promise<void>;
}) {
  const { can } = useAuth();
  const [customer, setCustomer] = useState<Customer | null>(null);
  const [editing, setEditing] = useState(false);
  const [email, setEmail] = useState('');
  const [phone, setPhone] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);

  useEffect(() => {
    api<Customer>(`/api/v1/customers/${customerId}`)
      .then((c) => {
        setCustomer(c);
        setEmail(c.email);
        setPhone(c.phoneNumber);
      })
      .catch((e) => setError(errorMessage(e)));
  }, [customerId]);

  async function save(e: FormEvent) {
    e.preventDefault();
    if (!customer) return;

    const changes: Record<string, string> = {};
    if (email.trim() !== customer.email) changes.email = email.trim();
    if (phone.trim() !== customer.phoneNumber) {
      changes.phoneNumber = phone.trim();
      changes.countryCode = 'NG';
    }

    if (Object.keys(changes).length === 0) {
      setEditing(false);
      return;
    }

    setBusy(true);
    setError(null);
    setDone(null);
    try {
      const updated = await api<Customer>(`/api/v1/customers/${customerId}`, {
        method: 'PATCH',
        body: changes,
      });
      setCustomer(updated);
      setEditing(false);
      setDone('Contact details updated.');
      await onChanged();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  const editable = can('CUSTOMER_UPDATE') && !isStaffMember;

  return (
    <>
      <h3>Contact details</h3>
      {!customer ? (
        <p className="muted small">{error ?? 'Loading…'}</p>
      ) : editing ? (
        <form className="form" onSubmit={save}>
          <label className="field">
            <span className="field-label">Email</span>
            <input type="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
          </label>
          <label className="field">
            <span className="field-label">Phone number</span>
            <input required value={phone} onChange={(e) => setPhone(e.target.value)} placeholder="e.g. 08031234567" />
          </label>
          <div className="actions">
            <button type="button" className="btn btn-ghost" onClick={() => setEditing(false)} disabled={busy}>
              Cancel
            </button>
            <button className="btn btn-primary" disabled={busy}>
              Save
            </button>
          </div>
        </form>
      ) : (
        <dl className="kv">
          <dt>Email</dt>
          <dd>{customer.email}</dd>
          <dt>Phone</dt>
          <dd>{customer.phoneNumber}</dd>
        </dl>
      )}
      {customer && !editing && editable && (
        <button className="btn btn-ghost btn-sm" onClick={() => setEditing(true)}>
          Edit contact details
        </button>
      )}
      {customer && isStaffMember && can('CUSTOMER_UPDATE') && (
        <p className="muted small">This is a staff member. Their details are managed by the System Admin, not from here.</p>
      )}
      {done && <Notice tone="good">{done}</Notice>}
      {customer && error && <Notice tone="bad">{error}</Notice>}
    </>
  );
}

type AccountAction = 'activate' | 'freeze' | 'unfreeze' | 'close';

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
          {manager && <p className="muted small">An account can only be activated once its KYC is verified.</p>}
        </>
      )}
      {done && <Notice tone="good">{done}</Notice>}
      {error && <Notice tone="bad">{error}</Notice>}
    </>
  );
}
