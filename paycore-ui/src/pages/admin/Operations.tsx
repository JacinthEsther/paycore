import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, errorMessage } from '../../api/client';
import type { OpsRequest } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { ScreenTitle } from '../../components/Layout';
import { formatAccountNumber, formatDate, formatMoney, humanize, Notice, StatusBadge } from '../../components/ui';
import { markDone } from '../../journey';

export function Operations() {
  const { can, session } = useAuth();
  const [pending, setPending] = useState<OpsRequest[] | null>(null);
  const [decided, setDecided] = useState<OpsRequest[]>([]);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const [open, done] = await Promise.all([
        api<OpsRequest[]>('/api/v1/ops/requests'),
        api<OpsRequest[]>('/api/v1/ops/requests?status=DECIDED'),
      ]);
      setPending(open);
      setDecided(done);
    } catch (e) {
      setError(errorMessage(e));
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <>
      <ScreenTitle title="Approvals">
        An Officer requests every correction; a Supervisor approves it before any money moves.
      </ScreenTitle>

      {!can('LEDGER_APPROVE') && <Notice tone="info">You file requests; a Supervisor approves them.</Notice>}
      {!can('LEDGER_REQUEST') && (
        <Notice tone="info">You approve or reject what officers file; you cannot file requests yourself.</Notice>
      )}
      {error && <Notice tone="bad">{error}</Notice>}

      <section className="panel">
        <header className="panel-head">
          <h2>Waiting for approval</h2>
          {can('LEDGER_REQUEST') && (
            <span className="muted small">
              File a <Link to="/admin/ledger">reversal</Link>, an <Link to="/admin/customers">adjustment</Link> or a{' '}
              <Link to="/admin/payments">payment resolution</Link>
            </span>
          )}
        </header>
        {pending === null ? (
          <p className="muted">Loading…</p>
        ) : pending.length === 0 ? (
          <p className="empty-state">Nothing waiting. Requests filed by Operations Officers appear here.</p>
        ) : (
          <ul className="ops-list">
            {pending.map((request) => (
              <PendingRequest
                key={request.id}
                request={request}
                mine={request.requestedBy === session?.customerId}
                canApprove={can('LEDGER_APPROVE')}
                onDecided={load}
              />
            ))}
          </ul>
        )}
      </section>

      <section className="panel">
        <header className="panel-head">
          <h2>Recently decided</h2>
        </header>
        {decided.length === 0 ? (
          <p className="empty-state">No decisions yet.</p>
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>Request</th>
                <th>Amount</th>
                <th>Maker</th>
                <th>Checker</th>
                <th>Outcome</th>
              </tr>
            </thead>
            <tbody>
              {decided.map((request) => (
                <tr key={request.id}>
                  <td>
                    {describe(request)}
                    <span className="muted small block">{request.reason}</span>
                  </td>
                  <td>{formatMoney(request.amount, request.currency)}</td>
                  <td>{request.requestedByName}</td>
                  <td>{request.decidedByName}</td>
                  <td>
                    <StatusBadge status={request.status} />
                    {request.resultReference && <code className="block small">{request.resultReference}</code>}
                    {request.status === 'REJECTED' && request.decisionNote && (
                      <span className="muted small block">“{request.decisionNote}”</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </>
  );
}

function describe(request: OpsRequest) {
  if (request.type === 'REVERSAL') return <>Reverse <code>{request.transactionReference}</code></>;
  if (request.type === 'PAYMENT_RESOLUTION') {
    return (
      <>
        {humanize(request.resolution ?? '')} payment{' '}
        <Link to={`/admin/payments?status=ALL&id=${request.paymentId}`}>
          <code>{request.paymentReference}</code>
        </Link>{' '}
        · {request.accountName}
      </>
    );
  }
  return (
    <>
      {request.direction === 'CREDIT' ? 'Credit' : 'Debit'} {request.accountName} ·{' '}
      {request.accountNumber && formatAccountNumber(request.accountNumber)}
    </>
  );
}

function PendingRequest({
  request,
  mine,
  canApprove,
  onDecided,
}: {
  request: OpsRequest;
  mine: boolean;
  canApprove: boolean;
  onDecided: () => Promise<void>;
}) {
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function decide(action: 'approve' | 'reject') {
    setBusy(true);
    setError(null);
    try {
      await api(`/api/v1/ops/requests/${request.id}/${action}`, { body: { note: note.trim() || null } });
      if (action === 'approve') markDone('ops-approve');
      await onDecided();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <li className="ops-item">
      <div className="ops-main">
        <span className={`chip ${request.type === 'ADJUSTMENT' ? 'chip-info' : 'chip-warn'}`}>
          {request.type === 'REVERSAL' ? 'Reversal' : request.type === 'PAYMENT_RESOLUTION' ? 'Payment' : 'Adjustment'}
        </span>
        <strong>{describe(request)}</strong>
        <span className="ops-amount">{formatMoney(request.amount, request.currency)}</span>
      </div>
      {request.transactionDescription && <p className="muted small">Transaction: {request.transactionDescription}</p>}
      <p>
        <span className="muted small">Reason: </span>
        {request.reason}
      </p>
      {request.customerDescription && (
        <p className="muted small">Customer will see: “{request.customerDescription}”</p>
      )}
      <p className="muted small">
        Requested by <strong>{request.requestedByName}</strong>
        {mine && ' (you)'} · {formatDate(request.requestedAt)}
      </p>

      {canApprove && (
        <div className="ops-actions">
          <input
            value={note}
            maxLength={500}
            placeholder={mine ? 'Another officer must decide this' : 'Note (required to reject)'}
            onChange={(e) => setNote(e.target.value)}
          />
          <button className="btn btn-ghost btn-sm" disabled={busy} onClick={() => decide('reject')}>
            Reject
          </button>
          <button className="btn btn-primary btn-sm" disabled={busy} onClick={() => decide('approve')}>
            Approve
          </button>
        </div>
      )}
      {mine && canApprove && (
        <p className="muted small">Try approving it yourself: the API answers 403 FOUR_EYES_REQUIRED.</p>
      )}
      {error && <Notice tone="bad">{error}</Notice>}
    </li>
  );
}
