import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api, errorMessage } from '../../api/client';
import type { OpsRequest, StaffTransaction, SystemAccount } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { ScreenTitle } from '../../components/Layout';
import { typeLabel } from '../../components/TransactionList';
import { accountLabel, Card, Field, formatAccountNumber, formatDate, formatMoney, humanize, Notice, StatusBadge } from '../../components/ui';
import { markDone } from '../../journey';

/**
 * Staff view of the ledger: what PayCore holds, and any transaction by
 * reference with its internal note. Staff cannot move money here; an
 * operations officer can only request a reversal, which a second officer
 * must approve.
 */
export function Ledger() {
  const [params, setParams] = useSearchParams();
  const reference = params.get('ref') ?? '';

  return (
    <>
      <ScreenTitle title="Transactions">
        Every movement is a debit and an equal credit. Posted entries are never edited: a mistake is undone by a new
        reversal transaction, and only after two operations officers agree.
      </ScreenTitle>

      <div className="grid-2">
        <SystemAccounts />
        <FindTransaction reference={reference} onSearch={(ref) => setParams(ref ? { ref } : {})} />
      </div>

      {reference && <TransactionDetail key={reference} reference={reference} />}
    </>
  );
}

function SystemAccounts() {
  const [accounts, setAccounts] = useState<SystemAccount[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api<SystemAccount[]>('/api/v1/admin/ledger/system-accounts')
      .then(setAccounts)
      .catch((e) => setError(errorMessage(e)));
  }, []);

  return (
    <Card title="What PayCore holds">
      {error && <Notice tone="bad">{error}</Notice>}
      {accounts?.length === 0 && <p className="muted small">No money has come in yet, so no settlement account exists.</p>}
      {accounts?.map((account) => (
        <p key={account.accountId} className="big-money">
          {formatMoney(account.balance, account.currency)}
          <span className="muted small block">
            {humanize(account.type)} account · {account.currency}
          </span>
        </p>
      ))}
      <p className="muted small">
        The settlement account is the money PayCore holds at its bank: the other side of every top-up and every
        transfer in or out of PayCore. It always equals the sum of all customer balances, which is what reconciliation
        checks against the bank statement.
      </p>
    </Card>
  );
}

function FindTransaction({ reference, onSearch }: { reference: string; onSearch: (ref: string) => void }) {
  const [value, setValue] = useState(reference);

  useEffect(() => setValue(reference), [reference]);

  function submit(e: FormEvent) {
    e.preventDefault();
    onSearch(value.trim());
  }

  return (
    <Card title="Find a transaction">
      <form className="form" onSubmit={submit}>
        <Field label="Reference" hint="From a customer's receipt or statement, e.g. TXN-20260930-8F3A2C91D0B47E15.">
          <input value={value} placeholder="TXN-…" onChange={(e) => setValue(e.target.value)} />
        </Field>
        <button className="btn btn-primary" disabled={!value.trim()}>
          Look up
        </button>
      </form>
    </Card>
  );
}

function TransactionDetail({ reference }: { reference: string }) {
  const { can } = useAuth();
  const [transaction, setTransaction] = useState<StaffTransaction | null>(null);
  const [filed, setFiled] = useState<OpsRequest | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setTransaction(
        await api<StaffTransaction>(`/api/v1/admin/ledger/transactions/reference/${encodeURIComponent(reference)}`),
      );
      setError(null);
    } catch (e) {
      setError(errorMessage(e));
    }
  }, [reference]);

  useEffect(() => {
    void load();
  }, [load]);

  if (error) return <Notice tone="bad">{error}</Notice>;
  if (!transaction) return <p className="muted">Loading…</p>;

  const reversible = transaction.status === 'POSTED' && transaction.type !== 'REVERSAL';

  return (
    <div className="grid-2">
      <Card title={<code>{transaction.reference}</code>} aside={<StatusBadge status={transaction.status} />}>
        <dl className="kv">
          <dt>Type</dt>
          <dd>{typeLabel(transaction.type)}</dd>
          <dt>Amount</dt>
          <dd>{formatMoney(transaction.amount, transaction.currency)}</dd>
          <dt>Customer sees</dt>
          <dd>{transaction.description ?? '—'}</dd>
          {transaction.counterparty && (
            <>
              <dt>Other bank</dt>
              <dd>
                {transaction.counterparty.name} · {transaction.counterparty.bank} ·{' '}
                {formatAccountNumber(transaction.counterparty.accountNumber)}
              </dd>
            </>
          )}
          {transaction.provider && (
            <>
              <dt>Carried by</dt>
              <dd>
                {transaction.provider} · <code className="wrap">{transaction.providerReference}</code>
              </dd>
            </>
          )}
          <dt>Staff note</dt>
          <dd>{transaction.staffNote ? <em>{transaction.staffNote}</em> : <span className="muted">None (no staff involved)</span>}</dd>
          {transaction.approvedBy && (
            <>
              <dt>Four eyes</dt>
              <dd>
                <span className="muted small">
                  requested by <code>{transaction.initiatedBy?.slice(0, 8)}</code>, approved by{' '}
                  <code>{transaction.approvedBy.slice(0, 8)}</code>
                </span>
              </dd>
            </>
          )}
          <dt>Posted</dt>
          <dd>{formatDate(transaction.postedAt)}</dd>
          {transaction.reversedAt && (
            <>
              <dt>Reversed</dt>
              <dd>{formatDate(transaction.reversedAt)}</dd>
            </>
          )}
        </dl>

        <table className="ledger-table">
          <thead>
            <tr>
              <th>Account</th>
              <th className="num">Debit</th>
              <th className="num">Credit</th>
            </tr>
          </thead>
          <tbody>
            {transaction.entries.map((entry) => (
              <tr key={entry.id}>
                <td>
                  {entry.accountName}
                  <span className="muted small block">{accountLabel(entry.accountNumber)}</span>
                </td>
                <td className="num money-out">{entry.type === 'DEBIT' ? formatMoney(entry.amount, entry.currency) : ''}</td>
                <td className="num money-in">{entry.type === 'CREDIT' ? formatMoney(entry.amount, entry.currency) : ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>

      {filed ? (
        <Card title="Reversal requested">
          <Notice tone="good">
            Filed for approval. Nothing has moved yet: a different operations officer must approve it.
          </Notice>
          <Link to="/admin/operations" className="small">
            Open the approvals queue →
          </Link>
        </Card>
      ) : !reversible ? (
        <Card title="Reverse">
          <p className="muted small">
            {transaction.type === 'REVERSAL'
              ? 'A reversal is never reversed. If it was a mistake, request an adjustment instead.'
              : 'Already reversed. A transaction can only be reversed once.'}
          </p>
        </Card>
      ) : can('LEDGER_REQUEST') ? (
        <RequestReversal transaction={transaction} onFiled={setFiled} />
      ) : (
        <Card title="Reverse">
          <p className="muted small">
            Only operations officers can request a reversal, and another officer has to approve it. Admins manage
            customers and compliance, not money.
          </p>
        </Card>
      )}
    </div>
  );
}

function RequestReversal({ transaction, onFiled }: { transaction: StaffTransaction; onFiled: (r: OpsRequest) => void }) {
  const [reason, setReason] = useState('');
  const [description, setDescription] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const request = await api<OpsRequest>('/api/v1/ops/requests/reversals', {
        body: { transactionId: transaction.id, reason: reason.trim(), customerDescription: description.trim() || null },
      });
      markDone('ops-request');
      onFiled(request);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card title="Request a reversal">
      <form className="form" onSubmit={submit}>
        <p className="muted small">
          Undoes it with a new transaction, same accounts and amount, sides swapped. It is only posted once a second
          officer approves, and is refused if the money is gone, an account is closed, or either of you owns one.
        </p>
        <Field label="Reason (required, internal)" hint="Only staff ever see this.">
          <input value={reason} maxLength={500} required placeholder="e.g. Sent to the wrong account" onChange={(e) => setReason(e.target.value)} />
        </Field>
        <Field label="Customer description (optional)" hint={`Left empty: "Reversal of ${transaction.reference}".`}>
          <input value={description} maxLength={500} onChange={(e) => setDescription(e.target.value)} />
        </Field>
        {error && <Notice tone="bad">{error}</Notice>}
        <button className="btn btn-primary" disabled={busy}>
          {busy ? 'Filing…' : 'Request reversal'}
        </button>
      </form>
    </Card>
  );
}
