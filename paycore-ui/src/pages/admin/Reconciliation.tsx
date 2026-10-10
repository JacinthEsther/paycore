import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api, errorMessage } from '../../api/client';
import type { ReconciliationItem, ReconciliationRun, ReconciliationRunDetail } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { ScreenTitle } from '../../components/Layout';
import { Card, Field, formatDate, formatMoney, humanize, Notice, StatusBadge } from '../../components/ui';

const PROVIDERS = { BANK_RAIL: 'Bank rail (NIP)', CARD: 'Card processor' } as const;

const KINDS = { OUTBOUND: 'Transfer out', INBOUND: 'Transfer in', CARD: 'Card top-up' } as const;

function lagosToday() {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Africa/Lagos' }).format(new Date());
}

export function Reconciliation() {
  const { can } = useAuth();
  const [params, setParams] = useSearchParams();
  const selected = params.get('run');
  const [runs, setRuns] = useState<ReconciliationRun[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setRuns(await api<ReconciliationRun[]>('/api/v1/ops/reconciliation/runs'));
      setError(null);
    } catch (e) {
      setError(errorMessage(e));
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <>
      <ScreenTitle title="Reconciliation">
        Each provider's daily report, matched line by line against PayCore's books.
      </ScreenTitle>

      {error && <Notice tone="bad">{error}</Notice>}

      <div className="grid-2">
        {can('LEDGER_REQUEST') ? (
          <StartRun onRun={async (run) => {
            await load();
            setParams({ run: run.id });
          }} />
        ) : (
          <Card title="Run reconciliation">
            <p className="muted small">Operations Officers run reconciliation and work the breaks; Supervisors close them.</p>
          </Card>
        )}
        <Card title="Make a break to find">
          <p className="muted small">The simulated providers misbehave on these amounts, so every kind of break can be tried:</p>
          <dl className="kv small">
            <dt>
              <code>.95</code>
            </dt>
            <dd>Send from Test Bank (Add money): the rail's notification is lost. <em>Missing locally</em>.</dd>
            <dt>
              <code>.93</code>
            </dt>
            <dd>Transfer to Test Bank: accepted, then returned by the bank. <em>Status mismatch</em>.</dd>
            <dt>
              <code>.94</code>
            </dt>
            <dd>Card top-up: the processor charges N1.00 more. <em>Amount mismatch</em>.</dd>
            <dt>
              <code>.92</code>
            </dt>
            <dd>Card top-up: the processor never settles it. <em>Missing at the provider</em>.</dd>
            <dt>
              <code>.96</code>
            </dt>
            <dd>Either: PayCore never learns the outcome. <em>Status mismatch</em> until resolved.</dd>
          </dl>
        </Card>
      </div>

      <section className="panel">
        <header className="panel-head">
          <h2>Runs</h2>
        </header>
        {runs === null ? (
          <p className="muted">Loading…</p>
        ) : runs.length === 0 ? (
          <p className="empty-state">No runs yet. Run one above, or wait for the morning's scheduled run.</p>
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>Day</th>
                <th>Provider</th>
                <th>Matched</th>
                <th>Breaks</th>
                <th>Provider moved</th>
                <th>PayCore recorded</th>
                <th>Run</th>
              </tr>
            </thead>
            <tbody>
              {runs.map((run) => (
                <tr
                  key={run.id}
                  className={run.id === selected ? 'selected' : ''}
                  style={{ cursor: 'pointer' }}
                  onClick={() => setParams({ run: run.id })}
                >
                  <td>{run.businessDate}</td>
                  <td>{PROVIDERS[run.providerKind]}</td>
                  <td>{run.matched}</td>
                  <td>{run.breaks === 0 ? <StatusBadge status="MATCHED" label="None" /> : <StatusBadge status="MISSING_LOCAL" label={String(run.breaks)} />}</td>
                  <td>{formatMoney(run.providerTotal, 'NGN')}</td>
                  <td>{formatMoney(run.paycoreTotal, 'NGN')}</td>
                  <td className="small muted">
                    {formatDate(run.createdAt)} · {run.scheduled ? 'scheduled' : 'by an officer'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      {selected && <RunDetail key={selected} runId={selected} />}
    </>
  );
}

function StartRun({ onRun }: { onRun: (run: ReconciliationRun) => Promise<void> }) {
  const [provider, setProvider] = useState<keyof typeof PROVIDERS>('BANK_RAIL');
  const [day, setDay] = useState(lagosToday());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await onRun(await api<ReconciliationRun>('/api/v1/ops/reconciliation/runs', { body: { provider, businessDate: day } }));
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card title="Run reconciliation">
      <form className="form" onSubmit={submit}>
        <Field label="Provider">
          <select value={provider} onChange={(e) => setProvider(e.target.value as keyof typeof PROVIDERS)}>
            {Object.entries(PROVIDERS).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Business day" hint="A calendar day in Lagos time.">
          <input type="date" value={day} max={lagosToday()} required onChange={(e) => setDay(e.target.value)} />
        </Field>
        {error && <Notice tone="bad">{error}</Notice>}
        <button className="btn btn-primary" disabled={busy}>
          {busy ? 'Reconciling…' : 'Reconcile'}
        </button>
      </form>
    </Card>
  );
}

function RunDetail({ runId }: { runId: string }) {
  const [detail, setDetail] = useState<ReconciliationRunDetail | null>(null);
  const [breaksOnly, setBreaksOnly] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setDetail(await api<ReconciliationRunDetail>(`/api/v1/ops/reconciliation/runs/${runId}`));
    } catch (e) {
      setError(errorMessage(e));
    }
  }, [runId]);

  useEffect(() => {
    void load();
  }, [load]);

  if (error) return <Notice tone="bad">{error}</Notice>;
  if (!detail) return <p className="muted">Loading…</p>;

  const { run, items } = detail;
  const shown = breaksOnly ? items.filter((item) => item.result !== 'MATCHED') : items;
  const open = items.filter((item) => item.resolutionStatus === 'OPEN').length;

  return (
    <section className="panel">
      <header className="panel-head">
        <h2>
          {PROVIDERS[run.providerKind]} · {run.businessDate}
        </h2>
        <span className="muted small">
          {run.providerLines} provider lines · {run.paycoreRecords} PayCore records · {run.matched} matched ·{' '}
          {run.breaks} breaks ({open} open)
        </span>
      </header>

      <div className="filters" role="tablist" aria-label="Show">
        <button role="tab" aria-selected={breaksOnly} className={breaksOnly ? 'active' : ''} onClick={() => setBreaksOnly(true)}>
          Breaks
        </button>
        <button role="tab" aria-selected={!breaksOnly} className={!breaksOnly ? 'active' : ''} onClick={() => setBreaksOnly(false)}>
          Everything
        </button>
      </div>

      {shown.length === 0 ? (
        <p className="empty-state">{breaksOnly ? 'No breaks: both sides agree on every payment that day.' : 'Nothing moved that day.'}</p>
      ) : (
        <ul className="ops-list">
          {shown.map((item) => (
            <ItemRow key={item.id} item={item} onChanged={load} />
          ))}
        </ul>
      )}
    </section>
  );
}

function side(amount: number | null, status: string | null, currency: string) {
  return status === null ? <span className="muted">No record</span> : (
    <>
      {formatMoney(amount ?? 0, currency)} <span className="muted small">· {humanize(status)}</span>
    </>
  );
}

function ItemRow({ item, onChanged }: { item: ReconciliationItem; onChanged: () => Promise<void> }) {
  const { can } = useAuth();
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ tone: 'good' | 'bad'; text: string } | null>(null);

  async function act(path: string, body: unknown, done: string) {
    setBusy(true);
    setMessage(null);
    try {
      await api(path, { method: 'POST', body });
      setMessage({ tone: 'good', text: done });
      await onChanged();
    } catch (e) {
      setMessage({ tone: 'bad', text: errorMessage(e) });
    } finally {
      setBusy(false);
    }
  }

  const open = item.resolutionStatus === 'OPEN';
  const canRedeliver = open && item.kind === 'INBOUND' && item.result === 'MISSING_LOCAL';
  const needsReversal =
    open && item.kind === 'OUTBOUND' && item.result === 'STATUS_MISMATCH' && item.providerStatus === 'FAILED' && item.ledgerTransactionId;

  return (
    <li className="ops-item">
      <div className="ops-main">
        <StatusBadge status={item.result} label={humanize(item.result)} />
        <strong>{KINDS[item.kind]}</strong> <code className="wrap small">{item.externalReference}</code>
        {item.resolutionStatus !== 'NOT_REQUIRED' && <StatusBadge status={item.resolutionStatus} label={humanize(item.resolutionStatus)} />}
      </div>

      <dl className="kv small">
        <dt>Provider</dt>
        <dd>{side(item.providerAmount, item.providerStatus, item.currency)}</dd>
        <dt>PayCore</dt>
        <dd>
          {side(item.paycoreAmount, item.paycoreStatus, item.currency)}
          {item.paymentId && (
            <>
              {' '}
              · <Link to={`/admin/payments?status=ALL&id=${item.paymentId}`}>payment</Link>
            </>
          )}
        </dd>
      </dl>

      {item.detail && <p className="small">{item.detail}</p>}

      {item.resolutionStatus === 'RESOLVED' && (
        <p className="muted small">
          Resolved {formatDate(item.resolvedAt)}: “{item.resolutionNote}”
        </p>
      )}

      {open && (
        <div className="ops-actions">
          {canRedeliver && can('LEDGER_REQUEST') && (
            <button
              className="btn btn-primary btn-sm"
              disabled={busy}
              onClick={() =>
                act(
                  `/api/v1/ops/reconciliation/items/${item.id}/redeliver`,
                  undefined,
                  'The rail delivered it again: the customer is credited and the break is resolved.',
                )
              }
            >
              Ask the rail to deliver it
            </button>
          )}
          {needsReversal && can('LEDGER_REQUEST') && (
            <button
              className="btn btn-ghost btn-sm"
              disabled={busy}
              onClick={() =>
                act(
                  '/api/v1/ops/requests/reversals',
                  {
                    transactionId: item.ledgerTransactionId,
                    reason: `Reconciliation: the rail reports session ${item.externalReference} returned`,
                    customerDescription: 'Transfer returned by the beneficiary bank',
                  },
                  'Reversal requested: another officer approves it under Approvals, then resolve this break.',
                )
              }
            >
              Request reversal of the debit
            </button>
          )}
          {can('LEDGER_APPROVE') && (
            <>
              <input
                value={note}
                maxLength={500}
                placeholder="How it was fixed or explained (required)"
                onChange={(e) => setNote(e.target.value)}
              />
              <button
                className="btn btn-ghost btn-sm"
                disabled={busy || !note.trim()}
                onClick={() => act(`/api/v1/ops/reconciliation/items/${item.id}/resolve`, { note: note.trim() }, 'Break resolved.')}
              >
                Resolve
              </button>
            </>
          )}
        </div>
      )}
      {message && <Notice tone={message.tone}>{message.text}</Notice>}
    </li>
  );
}
