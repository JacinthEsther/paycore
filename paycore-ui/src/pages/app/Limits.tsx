import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { api, errorMessage } from '../../api/client';
import { useDemoInfo } from '../../api/demo';
import type { KycTier, KycVerification } from '../../api/types';
import { ScreenTitle } from '../../components/Layout';
import { Field, formatDate, formatDay, formatMoney, Notice, StatusBadge } from '../../components/ui';
import { sampleDocument, TEST_NUMBERS, TEST_PERSON } from '../Kyc';

export function Limits() {
  const [tier, setTier] = useState<KycTier | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setTier(await api<KycTier>('/api/v1/kyc/tier'));
      setError(null);
    } catch (e) {
      setError(errorMessage(e));
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  if (error) return <Notice tone="bad">{error}</Notice>;
  if (!tier) return <p className="muted">Loading…</p>;

  const used = tier.dailySend > 0 ? Math.min(100, (tier.sentToday / tier.dailySend) * 100) : 100;
  const missingNumber = tier.nextTierNeeds
    .map((need) => /Verify your (BVN|NIN)/.exec(need)?.[1] as 'BVN' | 'NIN' | undefined)
    .find(Boolean);
  const upgrade = tier.upgrade;
  const pending = upgrade?.status === 'PENDING';

  return (
    <>
      <ScreenTitle title="Your tier" back="/app/profile">
        Your tier sets how much you can send each day and how much you can hold. Verify more about yourself to move up.
      </ScreenTitle>

      <section className="panel">
        <header className="panel-head">
          <h2>Tier {tier.tier}</h2>
          {tier.tier === 3 && <StatusBadge status="ACTIVE" label="Highest" />}
        </header>

        <div className="limit-row">
          <span>Sent today</span>
          <strong>
            {formatMoney(tier.sentToday, tier.currency)} <span className="muted">of {formatMoney(tier.dailySend, tier.currency)}</span>
          </strong>
        </div>
        <div className="meter" role="progressbar" aria-valuenow={Math.round(used)} aria-valuemin={0} aria-valuemax={100}>
          <span style={{ width: `${used}%` }} />
        </div>
        <p className="muted small">Resets {formatDate(tier.resetsAt)}</p>

        <div className="limit-row">
          <span>Maximum balance</span>
          <strong>{tier.maxBalance === null ? 'No limit' : formatMoney(tier.maxBalance, tier.currency)}</strong>
        </div>
      </section>

      <section className="tier-ladder" aria-label="Tiers">
        {tier.tiers.map((level) => {
          const state = level.tier < tier.tier ? 'done' : level.tier === tier.tier ? 'current' : level.tier === tier.nextTier ? 'next' : 'later';
          return (
            <div key={level.tier} className={`tier-card tier-${state}`}>
              <header>
                <strong>Tier {level.tier}</strong>
                <span className="chip">
                  {state === 'current' ? 'You are here' : state === 'done' ? 'Done' : state === 'next' ? (pending ? 'In review' : 'Next') : 'Later'}
                </span>
              </header>
              <dl>
                <dt>Send a day</dt>
                <dd>{formatMoney(level.dailySend, tier.currency)}</dd>
                <dt>Hold up to</dt>
                <dd>{level.maxBalance === null ? 'No limit' : formatMoney(level.maxBalance, tier.currency)}</dd>
              </dl>
              <p className="muted small">{level.requirement}</p>
            </div>
          );
        })}
      </section>

      {upgrade?.status === 'APPROVED' && upgrade.decidedAt && (
        <Notice tone="good" title={`You moved to tier ${upgrade.requestedTier} on ${formatDay(upgrade.decidedAt)}.`}>
          {upgrade.decidedAutomatically
            ? `Your documents passed every check during the ${tier.reviewWorkingDays}-working-day review, so the upgrade went through automatically.`
            : 'A compliance officer reviewed and approved your documents.'}
        </Notice>
      )}

      {tier.nextTier && (
        <section className="panel">
          <header className="panel-head">
            <h2>Upgrade to tier {tier.nextTier}</h2>
          </header>

          {pending && upgrade ? (
            <ol className="upgrade-steps">
              <li className="done">
                <strong>Request received</strong>
                <span className="muted small">{formatDate(upgrade.requestedAt)}</span>
              </li>
              <li className={upgrade.needsCloserLook ? 'attention' : 'done'}>
                <strong>{upgrade.needsCloserLook ? 'Our team is taking a closer look' : 'Automatic checks passed'}</strong>
                <span className="muted small">
                  {upgrade.needsCloserLook
                    ? 'Something needs a person to check it, so this may take longer. Compliance will contact you if they need anything.'
                    : 'Your identity, account and history all check out.'}
                </span>
              </li>
              <li>
                <strong>
                  {upgrade.needsCloserLook ? 'Decision by compliance' : `Upgraded by ${formatDay(upgrade.reviewDueAt)}`}
                </strong>
                <span className="muted small">
                  {upgrade.needsCloserLook
                    ? 'You keep your current limits until then.'
                    : `Reviews take up to ${tier.reviewWorkingDays} working days. If nothing changes before then, you move to tier ${upgrade.requestedTier} automatically; compliance may approve it sooner.`}
                </span>
              </li>
            </ol>
          ) : (
            <>
              {upgrade?.status === 'REJECTED' && (
                <Notice tone="warn" title="Your last request was declined.">
                  {upgrade.reason}
                </Notice>
              )}
              <h3>What you need</h3>
              <ul className="small">
                {tier.nextTierNeeds.map((need) => (
                  <li key={need}>{need}</li>
                ))}
              </ul>
              {missingNumber ? (
                <VerifyNumber type={missingNumber} onDone={load} />
              ) : tier.nextTier === 3 ? (
                <>
                  <p className="muted small">
                    Takes up to {tier.reviewWorkingDays} working days after you send it. If everything checks out, you are
                    upgraded automatically.
                  </p>
                  <AddressProof onDone={load} />
                </>
              ) : null}
            </>
          )}
        </section>
      )}
    </>
  );
}

function VerifyNumber({ type, onDone }: { type: 'BVN' | 'NIN'; onDone: () => void }) {
  const demo = useDemoInfo();
  const [form, setForm] = useState({ number: '', firstName: '', lastName: '', dateOfBirth: '' });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const { number, ...person } = form;
      const result = await api<KycVerification>(`/api/v1/kyc/${type.toLowerCase()}`, {
        body: { [type.toLowerCase()]: number, ...person },
      });
      if (result.result === 'PASSED') onDone();
      else setError(result.reason ?? `The ${type} check did not pass.`);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="form" onSubmit={submit}>
      <p className="muted small">Verify your {type}.</p>
      <Field label={type}>
        <input
          required
          inputMode="numeric"
          pattern="\d{11}"
          maxLength={11}
          value={form.number}
          onChange={(e) => setForm({ ...form, number: e.target.value.replace(/\D/g, '') })}
        />
      </Field>
      <div className="row-2">
        <Field label="First name">
          <input required value={form.firstName} onChange={(e) => setForm({ ...form, firstName: e.target.value })} />
        </Field>
        <Field label="Last name">
          <input required value={form.lastName} onChange={(e) => setForm({ ...form, lastName: e.target.value })} />
        </Field>
      </div>
      <Field label="Date of birth">
        <input required type="date" value={form.dateOfBirth} onChange={(e) => setForm({ ...form, dateOfBirth: e.target.value })} />
      </Field>
      {demo && (
        <button type="button" className="link small" onClick={() => setForm({ number: TEST_NUMBERS[type], ...TEST_PERSON })}>
          Fill in test data
        </button>
      )}
      {error && <Notice tone="bad">{error}</Notice>}
      <button className="btn btn-primary" disabled={busy}>
        {busy ? 'Checking…' : `Verify ${type}`}
      </button>
    </form>
  );
}

function AddressProof({ onDone }: { onDone: () => void }) {
  const demo = useDemoInfo();
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function send(blob: Blob, name: string) {
    setBusy(true);
    setError(null);
    try {
      const form = new FormData();
      form.append('file', blob, name);
      await api('/api/v1/kyc/tier/upgrade', { form, formSummary: { file: name } });
      onDone();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="form">
      <p className="muted small">Upload a utility bill or bank statement from the last 3 months.</p>
      <input type="file" accept="application/pdf,image/png,image/jpeg" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
      {error && <Notice tone="bad">{error}</Notice>}
      <div className="actions">
        {demo && (
          <button
            type="button"
            className="btn btn-ghost"
            disabled={busy}
            onClick={async () => send(await sampleDocument('PROOF_OF_ADDRESS'), 'sample-bill.png')}
          >
            Use a sample bill
          </button>
        )}
        <button className="btn btn-primary" disabled={busy || !file} onClick={() => file && send(file, file.name)}>
          {busy ? 'Uploading…' : 'Send for review'}
        </button>
      </div>
    </div>
  );
}
