import { Link } from 'react-router-dom';
import type { StatementLine, TransactionType } from '../api/types';
import { formatMoney, Icon } from './ui';

const TYPE_LABELS: Record<TransactionType, string> = {
  TRANSFER: 'PayCore transfer',
  DEPOSIT: 'Card top-up',
  INBOUND_TRANSFER: 'Bank transfer',
  OUTBOUND_TRANSFER: 'Bank transfer',
  ADJUSTMENT: 'Adjustment',
  REVERSAL: 'Reversal',
  WITHDRAWAL: 'Withdrawal',
};

export function typeLabel(type: TransactionType) {
  return TYPE_LABELS[type];
}

/** The line's headline, as a banking app writes it. */
export function lineTitle(line: StatementLine) {
  const credit = line.direction === 'CREDIT';

  if (line.transactionType === 'REVERSAL') return line.description ?? 'Reversal';
  if (line.transactionType === 'DEPOSIT') return 'Card top-up';
  if (line.counterpartyName) return `${credit ? 'From' : 'To'} ${line.counterpartyName}`;
  return line.description ?? typeLabel(line.transactionType);
}

function lineIcon(line: StatementLine) {
  if (line.transactionType === 'REVERSAL') return 'undo';
  if (line.transactionType === 'DEPOSIT') return 'card';
  return line.direction === 'CREDIT' ? 'receive' : 'send';
}

function dayLabel(iso: string) {
  const date = new Date(iso);
  const today = new Date();
  const yesterday = new Date();
  yesterday.setDate(today.getDate() - 1);

  if (date.toDateString() === today.toDateString()) return 'Today';
  if (date.toDateString() === yesterday.toDateString()) return 'Yesterday';
  return date.toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' });
}

/** Statement lines, newest first, grouped by day. */
export function TransactionList({
  lines,
  currency,
  limit,
  empty = 'No transactions yet.',
}: {
  lines: StatementLine[];
  currency: string;
  limit?: number;
  empty?: string;
}) {
  const newestFirst = [...lines].reverse().slice(0, limit ?? lines.length);

  if (!newestFirst.length) {
    return <p className="empty-state">{empty}</p>;
  }

  const groups: { day: string; lines: StatementLine[] }[] = [];
  for (const line of newestFirst) {
    const day = dayLabel(line.postedAt);
    const last = groups[groups.length - 1];
    if (last && last.day === day) last.lines.push(line);
    else groups.push({ day, lines: [line] });
  }

  return (
    <div className="txn-list">
      {groups.map((group) => (
        <section key={group.day}>
          <h3 className="txn-day">{group.day}</h3>
          <ul>
            {group.lines.map((line) => {
              const credit = line.direction === 'CREDIT';
              return (
                <li key={`${line.transactionId}-${line.direction}`}>
                  <Link to={`/app/transactions/${line.transactionId}`} className="txn-row">
                    <span className={`txn-icon ${credit ? 'txn-in' : 'txn-out'}`}>
                      <Icon name={lineIcon(line)} size={18} />
                    </span>
                    <span className="txn-main">
                      <span className="txn-title">{lineTitle(line)}</span>
                      <span className="txn-sub">
                        {line.counterpartyBank && line.transactionType !== 'TRANSFER'
                          ? line.counterpartyBank
                          : typeLabel(line.transactionType)}
                        {' · '}
                        {new Date(line.postedAt).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })}
                        {line.transactionStatus === 'REVERSED' && <span className="chip chip-warn">Reversed</span>}
                      </span>
                    </span>
                    <span className={`txn-amount ${credit ? 'amount-in' : 'amount-out'}`}>
                      {credit ? '+' : '−'}
                      {formatMoney(line.amount, currency)}
                    </span>
                  </Link>
                </li>
              );
            })}
          </ul>
        </section>
      ))}
    </div>
  );
}
