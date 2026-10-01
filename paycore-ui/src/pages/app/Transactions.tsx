import { useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, errorMessage } from '../../api/client';
import type { Transaction } from '../../api/types';
import { useBanking, useStatement } from '../../banking';
import { ScreenTitle } from '../../components/Layout';
import { typeLabel, TransactionList } from '../../components/TransactionList';
import { formatAccountNumber, formatDate, formatMoney, Icon, Notice } from '../../components/ui';

/** First and last day of the month `back` months ago, as ISO dates (local). */
function monthRange(back: number) {
  const now = new Date();
  const first = new Date(now.getFullYear(), now.getMonth() - back, 1);
  const last = back === 0 ? now : new Date(now.getFullYear(), now.getMonth() - back + 1, 0);
  const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  return { from: iso(first), to: iso(last), label: first.toLocaleDateString(undefined, { month: 'long', year: 'numeric' }) };
}

export function Transactions() {
  const { account, loading } = useBanking();
  const [back, setBack] = useState(0);
  const range = useMemo(() => monthRange(back), [back]);
  const { statement, error } = useStatement(account?.id, `?from=${range.from}&to=${range.to}&size=200`);

  if (loading) return <p className="muted">Loading…</p>;
  if (!account || account.status === 'PENDING') {
    return (
      <>
        <ScreenTitle title="Transactions" />
        <Notice tone="info">Your transactions appear here once your account is active.</Notice>
      </>
    );
  }

  return (
    <>
      <ScreenTitle title="Transactions" />

      <div className="month-switch">
        <button className="icon-btn" onClick={() => setBack(back + 1)} aria-label="Previous month" disabled={back >= 11}>
          ‹
        </button>
        <strong>{range.label}</strong>
        <button className="icon-btn" onClick={() => setBack(back - 1)} aria-label="Next month" disabled={back === 0}>
          ›
        </button>
      </div>

      {error && <Notice tone="bad">{error}</Notice>}

      {statement && (
        <>
          <div className="totals">
            <div>
              <span className="muted small">Money in</span>
              <strong className="amount-in">+{formatMoney(statement.totalCredits, statement.currency)}</strong>
            </div>
            <div>
              <span className="muted small">Money out</span>
              <strong className="amount-out">−{formatMoney(statement.totalDebits, statement.currency)}</strong>
            </div>
            <div>
              <span className="muted small">Closing balance</span>
              <strong>{formatMoney(statement.closingBalance, statement.currency)}</strong>
            </div>
          </div>

          <section className="panel">
            <TransactionList lines={statement.lines} currency={statement.currency} empty="No transactions in this month." />
          </section>
        </>
      )}
    </>
  );
}

export function TransactionReceipt() {
  const { transactionId = '' } = useParams();
  const { account } = useBanking();
  const [transaction, setTransaction] = useState<Transaction | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api<Transaction>(`/api/v1/ledger/transactions/${transactionId}`)
      .then(setTransaction)
      .catch((e) => setError(errorMessage(e)));
  }, [transactionId]);

  if (error) {
    return (
      <>
        <ScreenTitle title="Receipt" back="/app/transactions" />
        <Notice tone="bad">{error}</Notice>
      </>
    );
  }

  if (!transaction || !account) return <p className="muted">Loading…</p>;

  const mine = transaction.entries.find((entry) => entry.accountId === account.id);
  const other = transaction.entries.find((entry) => entry.accountId !== account.id);
  const credit = mine?.type === 'CREDIT';

  const counterparty =
    transaction.counterparty ??
    (other && other.accountNumber
      ? { name: other.accountName, bank: 'PayCore', accountNumber: other.accountNumber }
      : null);

  return (
    <>
      <ScreenTitle title="Receipt" back="/app/transactions" />
      <section className="panel receipt">
        <div className={`receipt-mark ${credit ? 'receipt-ok' : 'receipt-neutral'}`}>
          <Icon name={transaction.type === 'REVERSAL' ? 'undo' : credit ? 'receive' : 'send'} size={30} />
        </div>
        <p className={`review-amount ${credit ? 'amount-in' : ''}`}>
          {credit ? '+' : '−'}
          {formatMoney(transaction.amount, transaction.currency)}
        </p>
        <p className="muted">{transaction.description ?? typeLabel(transaction.type)}</p>
        {transaction.status === 'REVERSED' && (
          <Notice tone="warn">This transaction was reversed: its money went back. The reversal is a separate entry.</Notice>
        )}

        <dl className="review-list">
          <dt>Type</dt>
          <dd>{typeLabel(transaction.type)}</dd>
          {counterparty && (
            <>
              <dt>{credit ? 'From' : 'To'}</dt>
              <dd>
                <strong>{counterparty.name}</strong>
                <span className="muted small">
                  {counterparty.bank} · {formatAccountNumber(counterparty.accountNumber)}
                </span>
              </dd>
            </>
          )}
          <dt>Date</dt>
          <dd>{formatDate(transaction.postedAt ?? transaction.createdAt)}</dd>
          <dt>Status</dt>
          <dd>{transaction.status === 'REVERSED' ? 'Reversed' : 'Successful'}</dd>
          <dt>Reference</dt>
          <dd>
            <code>{transaction.reference}</code>
          </dd>
          {transaction.providerReference && (
            <>
              <dt>{transaction.type === 'DEPOSIT' ? 'Payment ref' : 'Session ID'}</dt>
              <dd>
                <code className="wrap">{transaction.providerReference}</code>
              </dd>
            </>
          )}
          {transaction.reversalOf && (
            <>
              <dt>Reverses</dt>
              <dd>
                <Link to={`/app/transactions/${transaction.reversalOf}`}>Original transaction</Link>
              </dd>
            </>
          )}
        </dl>
      </section>
    </>
  );
}
