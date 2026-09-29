import { useCallback, useEffect, useState } from 'react';
import { api, errorMessage } from '../../api/client';
import type { BvnAttempts, KycReviewItem, KycReviewQueue, KycStatus } from '../../api/types';
import { Card, formatDate, humanize, Notice, PageHeader, StatusBadge } from '../../components/ui';
import { markDone, useJourney } from '../../journey';
import { FinishCard } from './FinishCard';

const FILTERS: (KycStatus | 'ALL')[] = ['ALL', 'SUBMITTED', 'UNDER_REVIEW', 'ADDITIONAL_INFO_REQUIRED', 'VERIFIED', 'REJECTED'];

export function ReviewQueue() {
  const { customerEmail } = useJourney();
  const [filter, setFilter] = useState<KycStatus | 'ALL'>('ALL');
  const [queue, setQueue] = useState<KycReviewQueue | null>(null);
  const [openId, setOpenId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const query = filter === 'ALL' ? '' : `&status=${filter}`;
      setQueue(await api<KycReviewQueue>(`/api/v1/kyc/reviews?size=50${query}`));
      setError(null);
    } catch (e) {
      setError(errorMessage(e));
    }
  }, [filter]);

  useEffect(() => {
    void load();
  }, [load]);

  const mine = queue?.profiles.find((p) => p.customerEmail === customerEmail);

  return (
    <>
      <PageHeader eyebrow="Phase 2 · Admin" title="KYC review queue">
        These endpoints need the <code>KYC_REVIEW</code> permission, which is exactly why your customer token got{' '}
        <code>403</code>. The workflow runs <code>SUBMITTED → UNDER_REVIEW →</code> approve, reject or request more
        information. Every decision records who made it and why.
      </PageHeader>

      {mine && (mine.status === 'SUBMITTED' || mine.status === 'UNDER_REVIEW') && (
        <Notice tone="info" title="Your application is here.">
          {mine.customerName} ({mine.customerEmail}) is <strong>{humanize(mine.status)}</strong>. Open it below to review it.
        </Notice>
      )}

      <div className="filters" role="tablist" aria-label="Filter by status">
        {FILTERS.map((f) => (
          <button key={f} role="tab" aria-selected={filter === f} className={filter === f ? 'active' : ''} onClick={() => setFilter(f)}>
            {f === 'ALL' ? 'All' : humanize(f)}
          </button>
        ))}
      </div>

      {error && <Notice tone="bad">{error}</Notice>}

      <Card>
        {!queue ? (
          <p className="muted">Loading…</p>
        ) : queue.profiles.length === 0 ? (
          <p className="muted">No KYC profiles{filter === 'ALL' ? '' : ` in ${humanize(filter)}`}.</p>
        ) : (
          <ul className="queue">
            {queue.profiles.map((item) => (
              <li key={item.kycId} className={item.customerEmail === customerEmail ? 'mine' : ''}>
                <button className="queue-row" onClick={() => setOpenId(openId === item.kycId ? null : item.kycId)} aria-expanded={openId === item.kycId}>
                  <span className="queue-who">
                    <strong>{item.customerName}</strong>
                    {item.customerEmail === customerEmail && <span className="chip">You</span>}
                    <span className="muted small">{item.customerEmail}</span>
                  </span>
                  <span className="queue-facts small">
                    <span className={item.bvnPassed || item.ninPassed ? 'ok' : 'muted'}>
                      {item.bvnPassed || item.ninPassed
                        ? `✓ ${[item.bvnPassed && 'BVN', item.ninPassed && 'NIN'].filter(Boolean).join('+')}`
                        : '· no ID check'}
                    </span>
                    <span className={item.documentTypes.length ? 'ok' : 'muted'}>
                      {item.documentTypes.length ? `✓ ${item.documentTypes.length} doc${item.documentTypes.length > 1 ? 's' : ''}` : '· no docs'}
                    </span>
                  </span>
                  <StatusBadge status={item.status} />
                  <span className="muted small queue-date">{formatDate(item.updatedAt)}</span>
                </button>
                {openId === item.kycId && <ReviewPanel item={item} onChanged={load} />}
              </li>
            ))}
          </ul>
        )}
      </Card>

      <FinishCard />
    </>
  );
}

function ReviewPanel({ item, onChanged }: { item: KycReviewItem; onChanged: () => Promise<void> }) {
  const [reason, setReason] = useState('');
  const [attemptType, setAttemptType] = useState<'bvn' | 'nin'>('bvn');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [attempts, setAttempts] = useState<BvnAttempts | null>(null);

  async function act(path: string, body?: unknown, decision = false) {
    setBusy(true);
    setError(null);
    try {
      await api(`/api/v1/kyc/${item.kycId}/${path}`, { method: 'POST', body });
      if (decision) markDone('review');
      setReason('');
      await onChanged();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  async function loadAttempts(type: 'bvn' | 'nin' = attemptType) {
    try {
      setAttemptType(type);
      setAttempts(await api<BvnAttempts>(`/api/v1/kyc/${item.kycId}/${type}-attempts?size=10`));
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <div className="review-panel">
      <dl className="kv">
        <dt>KYC id</dt>
        <dd>
          <code>{item.kycId}</code>
        </dd>
        <dt>Documents</dt>
        <dd>{item.documentTypes.length ? item.documentTypes.map(humanize).join(', ') : '—'}</dd>
        <dt>BVN</dt>
        <dd>{item.bvnPassed ? 'Passed with the provider' : 'No passed check'}</dd>
        <dt>NIN</dt>
        <dd>{item.ninPassed ? 'Passed with the provider' : 'No passed check'}</dd>
        {item.reviewReason && (
          <>
            <dt>Last note</dt>
            <dd>{item.reviewReason}</dd>
          </>
        )}
      </dl>

      {item.status === 'SUBMITTED' && (
        <div className="actions">
          <button className="btn btn-primary" disabled={busy} onClick={() => act('start-review')}>
            Start review
          </button>
          <span className="muted small">Moves it to UNDER_REVIEW so the decision buttons become available.</span>
        </div>
      )}

      {item.status === 'UNDER_REVIEW' && (
        <div className="form">
          <div className="actions">
            <button className="btn btn-good" disabled={busy} onClick={() => act('approve', undefined, true)}>
              Approve
            </button>
          </div>
          <label className="field">
            <span className="field-label">Reason (shown to the customer; required to reject or request information)</span>
            <textarea rows={2} maxLength={1000} value={reason} onChange={(e) => setReason(e.target.value)} placeholder="e.g. Please upload a clearer photo of your ID" />
          </label>
          <div className="actions">
            <button className="btn btn-secondary" disabled={busy || !reason.trim()} onClick={() => act('request-information', { reason }, true)}>
              Request more information
            </button>
            <button className="btn btn-bad" disabled={busy || !reason.trim()} onClick={() => act('reject', { reason }, true)}>
              Reject
            </button>
          </div>
        </div>
      )}

      {!['SUBMITTED', 'UNDER_REVIEW'].includes(item.status) && (
        <p className="muted small">
          Nothing to review in {humanize(item.status)}. Try the actions anyway if you like: the backend will answer with{' '}
          <code>409 INVALID_KYC_STATE</code>.{' '}
          <button className="link" onClick={() => act('approve')}>
            Try approving
          </button>
        </p>
      )}

      <div className="attempts">
        {attempts ? (
          <>
            <div className="filters" role="tablist" aria-label="Check type">
              {(['bvn', 'nin'] as const).map((t) => (
                <button key={t} role="tab" aria-selected={attemptType === t} className={attemptType === t ? 'active' : ''} onClick={() => loadAttempts(t)}>
                  {t.toUpperCase()} attempts
                </button>
              ))}
            </div>
            <p className="small">
              <strong>{attemptType.toUpperCase()} retry limit:</strong> {attempts.failedAttemptsCounted}/{attempts.maxFailedAttempts} failed checks count in the
              current {attempts.attemptWindow.replace('PT', '').toLowerCase()} window ·{' '}
              {attempts.limited ? <span className="bad">limited until {formatDate(attempts.retryAfter)}</span> : `${attempts.remainingAttempts} left`}
            </p>
            {attempts.attempts.length > 0 && (
              <ul className="small attempt-list">
                {attempts.attempts.map((a) => (
                  <li key={a.id}>
                    <StatusBadge status={a.result} /> {formatDate(a.createdAt)} · {a.provider}
                    {a.reason && <span className="muted"> · {a.reason}</span>}
                    {a.countsTowardLimit && <span className="chip chip-soft">counts</span>}
                  </li>
                ))}
              </ul>
            )}
            <button className="btn btn-ghost btn-sm" disabled={busy} onClick={() => act('bvn-attempts/reset').then(() => loadAttempts())}>
              Reset BVN &amp; NIN attempts
            </button>
          </>
        ) : (
          <button className="link" onClick={() => loadAttempts('bvn')}>
            Show BVN / NIN attempt history
          </button>
        )}
      </div>

      {error && <Notice tone="bad">{error}</Notice>}
    </div>
  );
}
