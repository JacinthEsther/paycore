import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api, errorMessage } from '../../api/client';
import type { OpsRequest, Payment, PaymentEvent, PaymentPage, PaymentResolution, PaymentStatus } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { ScreenTitle } from '../../components/Layout';
import { Card, Field, formatAccountNumber, formatDate, formatMoney, humanize, Notice, StatusBadge } from '../../components/ui';

const FILTERS: (PaymentStatus | 'ALL')[] = ['NEEDS_REVIEW', 'UNKNOWN', 'ALL'];

const PAYMENT_TYPES: Record<Payment['type'], string> = {
  OUTBOUND_TRANSFER: 'Transfer to another bank',
  CARD_FUNDING: 'Card top-up',
  BILL_PAYMENT: 'Bill payment',
};

const RESOLUTIONS: Record<Payment['type'], { value: PaymentResolution; label: string; effect: string }[]> = {
  OUTBOUND_TRANSFER: [
    { value: 'COMPLETE', label: 'Complete', effect: 'The beneficiary was paid. The debit stands; the payment SUCCEEDS.' },
    { value: 'REVERSE', label: 'Reverse', effect: 'They were not paid. The debit is reversed and the money returned; the payment FAILS.' },
  ],
  BILL_PAYMENT: [
    { value: 'COMPLETE', label: 'Complete', effect: 'The biller delivered. The debit stands; the payment SUCCEEDS.' },
    { value: 'REVERSE', label: 'Reverse', effect: 'Nothing was delivered. The debit is reversed and the money returned; the payment FAILS.' },
  ],
  CARD_FUNDING: [
    { value: 'CREDIT', label: 'Credit', effect: 'The card was charged. Credit the customer; the payment SUCCEEDS.' },
    { value: 'REFUND', label: 'Refund', effect: 'The card was charged but must not be credited. Refund it to the card; the payment FAILS.' },
    { value: 'CLOSE', label: 'Close', effect: 'The card was never charged. Nothing moves; the payment FAILS.' },
  ],
};

export function Payments() {
  const [params, setParams] = useSearchParams();
  const filter = (params.get('status') as PaymentStatus | 'ALL' | null) ?? 'NEEDS_REVIEW';
  const selected = params.get('id');
  const [page, setPage] = useState<PaymentPage | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const query = filter === 'ALL' ? '' : `?status=${filter}`;
      setPage(await api<PaymentPage>(`/api/v1/admin/payments${query}`));
      setError(null);
    } catch (e) {
      setError(errorMessage(e));
    }
  }, [filter]);

  useEffect(() => {
    void load();
  }, [load]);

  const byReference = params.get('ref');
  useEffect(() => {
    if (!byReference) return;
    api<Payment>(`/api/v1/admin/payments/reference/${encodeURIComponent(byReference)}`)
      .then((payment) => setParams({ status: 'ALL', id: payment.id }, { replace: true }))
      .catch((e) => setError(errorMessage(e)));
  }, [byReference, setParams]);

  function select(id: string | null, status = filter) {
    const next: Record<string, string> = { status };
    if (id) next.id = id;
    setParams(next);
  }

  return (
    <>
      <ScreenTitle title="Payments">
        Transfers to other banks, card top-ups and bills, and where each one stands.
      </ScreenTitle>

      <div className="filters" role="tablist" aria-label="Filter by status">
        {FILTERS.map((f) => (
          <button key={f} role="tab" aria-selected={filter === f} className={filter === f ? 'active' : ''} onClick={() => select(null, f)}>
            {f === 'ALL' ? 'All' : humanize(f)}
          </button>
        ))}
      </div>

      {error && <Notice tone="bad">{error}</Notice>}

      <div className="grid-2">
        <section className="panel">
          {page === null ? (
            <p className="muted">Loading…</p>
          ) : page.payments.length === 0 ? (
            <p className="empty-state">
              {filter === 'NEEDS_REVIEW'
                ? 'Nothing needs operations. Payments land here when automatic status checks give up, or a charge could not be credited.'
                : 'No payments here.'}
            </p>
          ) : (
            <table className="table">
              <thead>
                <tr>
                  <th>Payment</th>
                  <th>Amount</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {page.payments.map((payment) => (
                  <tr
                    key={payment.id}
                    className={payment.id === selected ? 'selected' : ''}
                    style={{ cursor: 'pointer' }}
                    onClick={() => select(payment.id)}
                  >
                    <td>
                      <code>{payment.reference}</code>
                      <span className="muted small block">
                        {PAYMENT_TYPES[payment.type]} · {formatDate(payment.createdAt)}
                      </span>
                    </td>
                    <td>{formatMoney(payment.amount, payment.currency)}</td>
                    <td>
                      <StatusBadge status={payment.status} label={humanize(payment.status)} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>

        {selected ? (
          <PaymentDetail key={selected} paymentId={selected} onChanged={load} />
        ) : (
          <Card title="Select a payment">
            <p className="muted small">Its history, the provider's references, and what operations can do about it.</p>
          </Card>
        )}
      </div>
    </>
  );
}

function PaymentDetail({ paymentId, onChanged }: { paymentId: string; onChanged: () => Promise<void> }) {
  const { can } = useAuth();
  const [payment, setPayment] = useState<Payment | null>(null);
  const [events, setEvents] = useState<PaymentEvent[]>([]);
  const [notice, setNotice] = useState<{ tone: 'good' | 'bad' | 'info'; text: string } | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      const [p, e] = await Promise.all([
        api<Payment>(`/api/v1/admin/payments/${paymentId}`),
        api<PaymentEvent[]>(`/api/v1/admin/payments/${paymentId}/events`),
      ]);
      setPayment(p);
      setEvents(e);
    } catch (err) {
      setNotice({ tone: 'bad', text: errorMessage(err) });
    }
  }, [paymentId]);

  useEffect(() => {
    void load();
  }, [load]);

  async function act(path: string, done: (p: Payment) => string) {
    setBusy(true);
    setNotice(null);
    try {
      const result = await api<Payment>(`/api/v1/admin/payments/${paymentId}/${path}`, { method: 'POST' });
      setNotice({ tone: 'info', text: done(result) });
      await load();
      await onChanged();
    } catch (err) {
      setNotice({ tone: 'bad', text: errorMessage(err) });
    } finally {
      setBusy(false);
    }
  }

  if (!payment) return notice ? <Notice tone="bad">{notice.text}</Notice> : <p className="muted">Loading…</p>;

  const open = payment.status === 'UNKNOWN' || payment.status === 'NEEDS_REVIEW';

  return (
    <div className="stack">
      <Card title={<code>{payment.reference}</code>} aside={<StatusBadge status={payment.status} label={humanize(payment.status)} />}>
        <dl className="kv">
          <dt>Type</dt>
          <dd>{PAYMENT_TYPES[payment.type]}</dd>
          <dt>Amount</dt>
          <dd>{formatMoney(payment.amount, payment.currency)}</dd>
          {payment.counterparty && (
            <>
              <dt>Beneficiary</dt>
              <dd>
                {payment.counterparty.name} · {payment.counterparty.bank} · {formatAccountNumber(payment.counterparty.accountNumber)}
              </dd>
            </>
          )}
          <dt>Provider</dt>
          <dd>
            {payment.provider} · <code className="wrap">{payment.externalReference}</code>
            <span className="muted small block">
              {payment.type === 'OUTBOUND_TRANSFER' ? 'NIP session id' : payment.type === 'BILL_PAYMENT' ? 'Request id' : 'Merchant reference'}, what PayCore asks the provider about
            </span>
          </dd>
          {payment.failureReason && (
            <>
              <dt>{payment.status === 'FAILED' ? 'Why it failed' : 'Customer is told'}</dt>
              <dd>{payment.failureReason}</dd>
            </>
          )}
          {payment.refundReference && (
            <>
              <dt>Refund</dt>
              <dd>
                <code>{payment.refundReference}</code>
              </dd>
            </>
          )}
          <dt>Status checks</dt>
          <dd>
            {payment.statusChecks}
            {payment.nextCheckAt && <span className="muted small"> · next {formatDate(payment.nextCheckAt)}</span>}
          </dd>
          {payment.ledgerTransactionId && (
            <>
              <dt>Ledger</dt>
              <dd className="small">
                {payment.type === 'CARD_FUNDING' ? 'Credit' : 'Debit'} <code>{payment.ledgerTransactionId.slice(0, 8)}</code>
                {payment.reversalTransactionId && (
                  <>
                    {' '}
                    · reversed by <code>{payment.reversalTransactionId.slice(0, 8)}</code>
                  </>
                )}
              </dd>
            </>
          )}
        </dl>

        {open && can('LEDGER_REQUEST') && (
          <div className="actions">
            <button
              className="btn btn-ghost btn-sm"
              disabled={busy}
              onClick={() => act('status-check', (p) => `Asked ${p.provider}: the payment is now ${humanize(p.status)}.`)}
            >
              Ask the provider now
            </button>
            {payment.status === 'UNKNOWN' && (
              <button
                className="btn btn-ghost btn-sm"
                disabled={busy}
                onClick={() => act('escalate', () => 'Taken out of automation: it can now be resolved under maker-checker.')}
              >
                Escalate to review
              </button>
            )}
          </div>
        )}
        {notice && <Notice tone={notice.tone}>{notice.text}</Notice>}
      </Card>

      {payment.status === 'NEEDS_REVIEW' && can('LEDGER_REQUEST') && (
        <RequestResolution payment={payment} onFiled={load} />
      )}

      <Card title="History">
        <ol className="timeline">
          {events.map((event, i) => (
            <li key={i} className={event.toStatus === 'FAILED' || event.toStatus === 'NEEDS_REVIEW' ? 'revoked' : ''}>
              <strong>{event.fromStatus ? `${humanize(event.fromStatus)} → ${humanize(event.toStatus)}` : humanize(event.toStatus)}</strong>
              <span className="muted small block">
                {formatDate(event.at)}
                {event.statusChecks > 0 && ` · after ${event.statusChecks} status check${event.statusChecks === 1 ? '' : 's'}`}
              </span>
              {event.failureReason && <span className="small block">{event.failureReason}</span>}
            </li>
          ))}
        </ol>
      </Card>
    </div>
  );
}

function RequestResolution({ payment, onFiled }: { payment: Payment; onFiled: () => Promise<void> }) {
  const options = RESOLUTIONS[payment.type];
  const [resolution, setResolution] = useState<PaymentResolution>(options[0].value);
  const [reason, setReason] = useState('');
  const [description, setDescription] = useState('');
  const [filed, setFiled] = useState<OpsRequest | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      setFiled(
        await api<OpsRequest>('/api/v1/ops/requests/payment-resolutions', {
          body: {
            paymentId: payment.id,
            resolution,
            reason: reason.trim(),
            customerDescription: description.trim() || null,
          },
        }),
      );
      await onFiled();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  if (filed) {
    return (
      <Card title="Resolution requested">
        <Notice tone="good">
          {humanize(filed.resolution ?? '')} filed. Nothing has changed yet: a different operations officer must approve it,
          and PayCore asks the provider once more before applying it.
        </Notice>
        <Link to="/admin/operations" className="small">
          Open the approvals queue →
        </Link>
      </Card>
    );
  }

  return (
    <Card title="Request a resolution">
      <form className="form" onSubmit={submit}>
        <p className="muted small">
          Only for what the provider cannot tell PayCore. If the provider gives a definite answer that contradicts your
          choice, the request is refused: run a status check instead.
        </p>
        <Field label="Resolution">
          <div className="stack">
            {options.map((option) => (
              <label key={option.value} className="small">
                <input
                  type="radio"
                  name="resolution"
                  value={option.value}
                  checked={resolution === option.value}
                  onChange={() => setResolution(option.value)}
                />{' '}
                <strong>{option.label}</strong>: {option.effect}
              </label>
            ))}
          </div>
        </Field>
        <Field label="Evidence (required, internal)" hint="What you checked outside PayCore. Only staff see this.">
          <input
            value={reason}
            maxLength={500}
            required
            placeholder="e.g. Rail support ticket 4411: session paid at 14:02"
            onChange={(e) => setReason(e.target.value)}
          />
        </Field>
        {resolution === 'REVERSE' && (
          <Field label="Customer description (optional)" hint="Shown on the customer's statement for the reversal.">
            <input value={description} maxLength={500} onChange={(e) => setDescription(e.target.value)} />
          </Field>
        )}
        {error && <Notice tone="bad">{error}</Notice>}
        <button className="btn btn-primary" disabled={busy}>
          {busy ? 'Filing…' : 'Request resolution'}
        </button>
      </form>
    </Card>
  );
}
