import { useCallback, useEffect, useState } from 'react';
import { api, errorMessage, openFile } from '../../api/client';
import type { KycTierUpgrade } from '../../api/types';
import { Card, formatDate, formatDay, humanize, Notice } from '../../components/ui';

export function TierUpgrades() {
  const [upgrades, setUpgrades] = useState<KycTierUpgrade[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setUpgrades(await api<KycTierUpgrade[]>('/api/v1/kyc/tier-upgrades'));
      setError(null);
    } catch (e) {
      setError(errorMessage(e));
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  if (upgrades?.length === 0 && !error) return null;

  return (
    <Card title="Tier upgrades">
      {error && <Notice tone="bad">{error}</Notice>}
      {!upgrades ? (
        <p className="muted">Loading…</p>
      ) : (
        <ul className="queue">
          {upgrades.map((upgrade) => (
            <UpgradeRow key={upgrade.id} upgrade={upgrade} onDecided={load} />
          ))}
        </ul>
      )}
    </Card>
  );
}

function UpgradeRow({ upgrade, onDecided }: { upgrade: KycTierUpgrade; onDecided: () => Promise<void> }) {
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function decide(action: 'approve' | 'reject') {
    setBusy(true);
    setError(null);
    try {
      await api(`/api/v1/kyc/tier-upgrades/${upgrade.id}/${action}`, {
        method: 'POST',
        body: action === 'reject' ? { reason } : undefined,
      });
      await onDecided();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <li>
      <div className="queue-row">
        <span className="queue-who">
          <strong>{upgrade.customerName}</strong>
          <span className="muted small">{upgrade.customerEmail}</span>
        </span>
        <span className="queue-facts small">
          Tier {upgrade.currentTier} → {upgrade.requestedTier} ·{' '}
          <button
            type="button"
            className="chip-btn"
            onClick={() => openFile(`/api/v1/kyc/documents/${upgrade.documentId}/content`).catch((e) => setError(errorMessage(e)))}
          >
            View {humanize(upgrade.documentType).toLowerCase()}
          </button>
        </span>
        <span className="muted small queue-date">{formatDate(upgrade.requestedAt)}</span>
      </div>

      {upgrade.flags.length > 0 ? (
        <Notice tone="warn" title="Flagged: needs your decision">
          {upgrade.flags.join('. ')}. This request will not be approved automatically.
        </Notice>
      ) : (
        <p className="muted small">
          Automatic checks passed. Approves automatically on {formatDay(upgrade.reviewDueAt)} unless something changes;
          approve or decline it sooner if you have looked at the document.
        </p>
      )}

      {rejecting ? (
        <div className="form">
          <input placeholder="Reason the customer will see" value={reason} onChange={(e) => setReason(e.target.value)} autoFocus />
          <div className="actions">
            <button className="btn btn-ghost btn-sm" onClick={() => setRejecting(false)} disabled={busy}>
              Cancel
            </button>
            <button className="btn btn-bad btn-sm" onClick={() => decide('reject')} disabled={busy || !reason.trim()}>
              Decline
            </button>
          </div>
        </div>
      ) : (
        <div className="actions">
          <button className="btn btn-ghost btn-sm" onClick={() => setRejecting(true)} disabled={busy}>
            Decline
          </button>
          <button className="btn btn-primary btn-sm" onClick={() => decide('approve')} disabled={busy}>
            Approve tier {upgrade.requestedTier}
          </button>
        </div>
      )}
      {error && <Notice tone="bad">{error}</Notice>}
    </li>
  );
}
