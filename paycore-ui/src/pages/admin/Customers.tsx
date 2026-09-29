import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { api, errorMessage } from '../../api/client';
import type { CustomerPage, CustomerRoles, CustomerSummary, RoleEvent } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { Card, formatDate, Notice, PageHeader, StatusBadge } from '../../components/ui';
import { markDone, useJourney } from '../../journey';
import { FinishCard } from './FinishCard';

const ROLES = ['CUSTOMER', 'SUPPORT', 'ADMIN'];

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
      <PageHeader eyebrow="Phase 2 · Admin" title="Customers, roles & audit">
        Role changes need <code>ROLE_MANAGE</code> and a reason. Each one goes into an append-only history along with
        the admin who made it. Admins can't change their own roles, and new permissions only take effect when the
        customer's next access token is issued.
      </PageHeader>

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
  const { session } = useAuth();
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
    void loadHistory();
  }, [loadHistory]);

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
      markDone('roles');
    });

  const revoke = (name: string) =>
    run(`Revoked ${name}.`, async () => {
      const r = await api<CustomerRoles>(`${base}/${name}/revoke`, { body: { reason } });
      setRoles(r.roles);
      setReason('');
      markDone('roles');
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

      {done && <Notice tone="good">{done}</Notice>}
      {error && <Notice tone="bad">{error}</Notice>}

      <h3>Role history</h3>
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
