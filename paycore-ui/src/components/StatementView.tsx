import { useCallback, useEffect, useState, type FormEvent, type ReactNode } from 'react';
import { api, errorMessage } from '../api/client';
import type { Statement, StatementLine } from '../api/types';
import { formatDate, formatMoney, humanize, Notice, StatusBadge } from './ui';

const PAGE_SIZE = 20;

/**
 * An account statement: period totals and every line with its running
 * balance. Empty dates mean the month to date, which is what the API
 * defaults to. Works against the customer endpoint or the staff one.
 */
export function StatementView({
  path,
  refreshKey = 0,
  lineAction,
}: {
  /** /api/v1/ledger/accounts/{id}/statement or the /admin equivalent */
  path: string;
  /** Change it to reload, e.g. after a transfer. */
  refreshKey?: number;
  /** Staff only: something to show at the end of a line, e.g. Reverse. */
  lineAction?: (line: StatementLine) => ReactNode;
}) {
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [page, setPage] = useState(0);
  const [statement, setStatement] = useState<Statement | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(
    async (range: { from: string; to: string }, pageNumber: number) => {
      const query = new URLSearchParams({ page: String(pageNumber), size: String(PAGE_SIZE) });
      if (range.from) query.set('from', range.from);
      if (range.to) query.set('to', range.to);
      try {
        setStatement(await api<Statement>(`${path}?${query}`));
        setError(null);
      } catch (e) {
        setError(errorMessage(e));
      }
    },
    [path],
  );

  // Dates only apply when the form is submitted, so typing does not refetch.
  const [applied, setApplied] = useState({ from: '', to: '' });

  useEffect(() => {
    void load(applied, page);
  }, [load, applied, page, refreshKey]);

  function submit(e: FormEvent) {
    e.preventDefault();
    setPage(0);
    setApplied({ from, to });
  }

  function monthToDate() {
    setFrom('');
    setTo('');
    setPage(0);
    setApplied({ from: '', to: '' });
  }

  const currency = statement?.currency ?? 'NGN';

  return (
    <div className="statement">
      <form className="statement-filter" onSubmit={submit}>
        <label>
          <span className="field-label">From</span>
          <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
        </label>
        <label>
          <span className="field-label">To</span>
          <input type="date" value={to} onChange={(e) => setTo(e.target.value)} />
        </label>
        <button className="btn btn-ghost" type="submit">
          Show
        </button>
        <button className="btn btn-ghost" type="button" onClick={monthToDate}>
          This month
        </button>
      </form>

      {error && <Notice tone="bad">{error}</Notice>}

      {statement && (
        <>
          <dl className="statement-totals">
            <div>
              <dt>Period</dt>
              <dd>
                {statement.from} to {statement.to}
                <span className="muted small block">{statement.timeZone} calendar days</span>
              </dd>
            </div>
            <div>
              <dt>Opening</dt>
              <dd>{formatMoney(statement.openingBalance, currency)}</dd>
            </div>
            <div>
              <dt>Money in</dt>
              <dd className="money-in">{formatMoney(statement.totalCredits, currency)}</dd>
            </div>
            <div>
              <dt>Money out</dt>
              <dd className="money-out">{formatMoney(statement.totalDebits, currency)}</dd>
            </div>
            <div>
              <dt>Closing</dt>
              <dd>
                <strong>{formatMoney(statement.closingBalance, currency)}</strong>
              </dd>
            </div>
          </dl>

          {statement.lines.length === 0 ? (
            <p className="muted small">No money moved in this period.</p>
          ) : (
            <div className="table-scroll">
              <table className="ledger-table">
                <thead>
                  <tr>
                    <th>Date</th>
                    <th>Details</th>
                    <th className="num">Money in</th>
                    <th className="num">Money out</th>
                    <th className="num">Balance</th>
                    {lineAction && <th aria-label="Actions" />}
                  </tr>
                </thead>
                <tbody>
                  {statement.lines.map((line) => (
                    <tr key={`${line.transactionId}-${line.direction}`}>
                      <td className="nowrap">{formatDate(line.postedAt)}</td>
                      <td>
                        {line.description ?? humanize(line.transactionType)}
                        <span className="muted small block">
                          <code>{line.reference}</code> · {humanize(line.transactionType)}
                          {line.transactionStatus === 'REVERSED' && (
                            <>
                              {' '}
                              <StatusBadge status="REVERSED" />
                            </>
                          )}
                        </span>
                      </td>
                      <td className="num money-in">{line.direction === 'CREDIT' ? formatMoney(line.amount, currency) : ''}</td>
                      <td className="num money-out">{line.direction === 'DEBIT' ? formatMoney(line.amount, currency) : ''}</td>
                      <td className="num">{formatMoney(line.balanceAfter, currency)}</td>
                      {lineAction && <td>{lineAction(line)}</td>}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {statement.page.totalPages > 1 && (
            <div className="actions">
              <button className="btn btn-ghost" disabled={page === 0} onClick={() => setPage(page - 1)}>
                ← Earlier
              </button>
              <span className="muted small">
                Page {page + 1} of {statement.page.totalPages} · {statement.page.totalElements} lines
              </span>
              <button className="btn btn-ghost" disabled={!statement.page.hasNext} onClick={() => setPage(page + 1)}>
                Later →
              </button>
            </div>
          )}
        </>
      )}
    </div>
  );
}
