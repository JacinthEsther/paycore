import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { api, errorMessage } from '../../api/client';
import type { Account, Balance, OpsRequest } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { StatementView } from '../../components/StatementView';
import { Field, formatMoney, Notice } from '../../components/ui';
import { markDone } from '../../journey';

/**
 * Staff view of one customer account's money: its balance and statement.
 * Staff cannot deposit or withdraw. An operations officer can request an
 * adjustment, posted only once a second officer approves it.
 */
export function AccountMoney({ account }: { account: Account }) {
  const { can } = useAuth();
  const [balance, setBalance] = useState<Balance | null>(null);

  const loadBalance = useCallback(async () => {
    try {
      setBalance(await api<Balance>(`/api/v1/admin/ledger/accounts/${account.id}/balance`));
    } catch {
      setBalance(null);
    }
  }, [account.id]);

  useEffect(() => {
    void loadBalance();
  }, [loadBalance]);

  return (
    <div className="account-money">
      <p>
        Balance <strong>{balance ? formatMoney(balance.balance, balance.currency) : '…'}</strong>
      </p>

      {can('LEDGER_REQUEST') && (account.status === 'ACTIVE' || account.status === 'FROZEN') && (
        <RequestAdjustment account={account} />
      )}

      <h4>Statement</h4>
      <StatementView
        path={`/api/v1/admin/ledger/accounts/${account.id}/statement`}
        lineAction={(line) => (
          <Link to={`/admin/ledger?ref=${encodeURIComponent(line.reference)}`} className="small">
            Open
          </Link>
        )}
      />
    </div>
  );
}

function RequestAdjustment({ account }: { account: Account }) {
  const [direction, setDirection] = useState<'CREDIT' | 'DEBIT'>('CREDIT');
  const [amount, setAmount] = useState('');
  const [reason, setReason] = useState('');
  const [description, setDescription] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [filed, setFiled] = useState<OpsRequest | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setFiled(null);
    try {
      setFiled(
        await api<OpsRequest>('/api/v1/ops/requests/adjustments', {
          body: {
            accountId: account.id,
            direction,
            amount: Number(amount),
            currency: account.currency,
            reason: reason.trim(),
            customerDescription: description.trim() || null,
          },
        }),
      );
      markDone('ops-request');
      setAmount('');
      setReason('');
      setDescription('');
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="form adjustment-form" onSubmit={submit}>
      <h4>Request an adjustment</h4>
      <p className="muted small">
        For corrections such as a fee charged twice. It is posted against PayCore's settlement account once a second
        operations officer approves it.
      </p>
      <div className="segmented segmented-sm" role="radiogroup">
        <button type="button" className={direction === 'CREDIT' ? 'active' : ''} onClick={() => setDirection('CREDIT')}>
          Credit customer
        </button>
        <button type="button" className={direction === 'DEBIT' ? 'active' : ''} onClick={() => setDirection('DEBIT')}>
          Debit customer
        </button>
      </div>
      <Field label={`Amount (${account.currency})`}>
        <input value={amount} type="number" min="0.01" step="0.01" required onChange={(e) => setAmount(e.target.value)} />
      </Field>
      <Field label="Reason (required, internal)">
        <input value={reason} maxLength={500} required placeholder="e.g. Card fee charged twice (ticket #4471)" onChange={(e) => setReason(e.target.value)} />
      </Field>
      <Field label="Customer description (optional)" hint='Left empty: "Account adjustment".'>
        <input value={description} maxLength={500} placeholder="e.g. Refund: duplicate fee" onChange={(e) => setDescription(e.target.value)} />
      </Field>
      {error && <Notice tone="bad">{error}</Notice>}
      {filed && (
        <Notice tone="good">
          Filed. Nothing has moved yet: <Link to="/admin/operations">a second officer must approve it</Link>.
        </Notice>
      )}
      <button className="btn btn-primary" disabled={busy}>
        {busy ? 'Filing…' : 'Request adjustment'}
      </button>
    </form>
  );
}
