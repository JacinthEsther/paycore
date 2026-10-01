import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, errorMessage } from '../../api/client';
import type { OpsRequest } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { ScreenTitle } from '../../components/Layout';
import { formatAccountNumber, formatDate, formatMoney, Notice, StatusBadge } from '../../components/ui';
import { markDone } from '../../journey';

/**
 * The maker-checker queue. Officers file reversals (from Transactions) and
 * adjustments (from Customers); a different officer approves or rejects
 * them here. Nothing moves until a second person approves.
 */
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
        Every manual correction needs two people. An officer files it with a reason; a different officer approves it,
        and only then does the ledger post it, recording both. The API refuses an officer approving their own request.
      </ScreenTitle>

      {!can('LEDGER_APPROVE') && <Notice tone="info">You can file requests but not approve them.</Notice>}
      {error && <Notice tone="bad">{error}</Notice>}

      <section className="panel">
        <header className="panel-head">
          <h2>Waiting for approval</h2>
          <span className="muted small">
            File a <Link to="/admin/ledger">reversal</Link> or an <Link to="/admin/customers">adjustment</Link>
          </span>
        </header>
        {pending === null ? (
          <p className="muted">Loading…</p>
        ) : pending.length === 0 ? (
          <p className="empty-state">Nothing waiting. Requests filed by officers appear here.</p>
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
        <span className={`chip ${request.type === 'REVERSAL' ? 'chip-warn' : 'chip-info'}`}>
          {request.type === 'REVERSAL' ? 'Reversal' : 'Adjustment'}
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
