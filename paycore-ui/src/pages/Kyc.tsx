import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { api, ApiError, errorMessage } from '../api/client';
import { useDemoInfo } from '../api/demo';
import type { Kyc as KycProfile, KycDocument, KycDocumentType, KycStatus, KycVerification } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { Card, Field, formatDate, humanize, Notice, PageHeader, StatusBadge } from '../components/ui';
import { markDone, useJourney } from '../journey';

type IdType = 'BVN' | 'NIN';

// Test numbers known to both the simulated provider and Dojah's sandbox.
const TEST_NUMBERS: Record<IdType, string> = { BVN: '22222222222', NIN: '70123456789' };
const TEST_PERSON = { firstName: 'John', lastName: 'Doe', dateOfBirth: '1990-01-01' };

const FLOW: KycStatus[] = ['NOT_STARTED', 'IN_PROGRESS', 'SUBMITTED', 'UNDER_REVIEW', 'VERIFIED'];

const DOCUMENT_TYPES: KycDocumentType[] = ['NATIONAL_ID', 'PASSPORT', 'DRIVERS_LICENSE', 'VOTERS_CARD', 'PROOF_OF_ADDRESS'];

export function Kyc() {
  const { profile } = useAuth();
  const { done } = useJourney();
  const [kyc, setKyc] = useState<KycProfile | null | 'none'>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [idResult, setIdResult] = useState<(KycVerification & { type: IdType }) | null>(null);
  const [passed, setPassed] = useState<IdType[]>([]);
  const [documents, setDocuments] = useState<KycDocument[]>([]);

  const load = useCallback(async () => {
    try {
      setKyc(await api<KycProfile>('/api/v1/kyc'));
    } catch (e) {
      if (e instanceof ApiError && e.status === 404) setKyc('none');
      else setError(errorMessage(e));
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (kyc && kyc !== 'none') {
      if (['SUBMITTED', 'UNDER_REVIEW', 'VERIFIED', 'REJECTED', 'ADDITIONAL_INFO_REQUIRED'].includes(kyc.status)) markDone('kyc');
      if ((kyc.status === 'VERIFIED' || kyc.status === 'REJECTED') && done.includes('review')) markDone('verified');
    }
  }, [kyc, done]);

  async function run(action: () => Promise<unknown>) {
    setBusy(true);
    setError(null);
    try {
      await action();
      await load();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  const status = kyc && kyc !== 'none' ? kyc.status : null;

  return (
    <>
      <PageHeader eyebrow="Step 4 · Customer" title="Identity verification">
        KYC is a state machine on the server. Each button calls one endpoint, and the backend refuses any step that
        doesn't fit the current state. Try submitting before you've checked a BVN or NIN to see it happen.
      </PageHeader>

      <KycProgress status={status} />

      {error && <Notice tone="bad">{error}</Notice>}

      {kyc === null && <p className="muted">Loading…</p>}

      {kyc === 'none' && (
        <Card title="No KYC profile yet">
          <p>Creating a profile starts you at <code>NOT_STARTED</code>. Each customer can have only one.</p>
          <button className="btn btn-primary" disabled={busy} onClick={() => run(() => api('/api/v1/kyc', { method: 'POST' }))}>
            Create KYC profile
          </button>
        </Card>
      )}

      {status === 'NOT_STARTED' && (
        <Card title="Ready when you are">
          <p>
            Starting moves the profile to <code>IN_PROGRESS</code>. BVN or NIN checks and document uploads are only accepted in
            that state.
          </p>
          <button className="btn btn-primary" disabled={busy} onClick={() => run(() => api('/api/v1/kyc/start', { method: 'POST' }))}>
            Start verification
          </button>
        </Card>
      )}

      {status === 'ADDITIONAL_INFO_REQUIRED' && kyc !== 'none' && kyc && (
        <Card title="The reviewer needs more information">
          <Notice tone="warn" title="Reviewer's note">
            {kyc.reviewReason}
          </Notice>
          <p>Restart the verification, add what was asked for, and submit again. Your earlier BVN or NIN result and documents still count, even if this page doesn't tick them.</p>
          <button className="btn btn-primary" disabled={busy} onClick={() => run(() => api('/api/v1/kyc/start', { method: 'POST' }))}>
            Restart verification
          </button>
        </Card>
      )}

      {status === 'IN_PROGRESS' && (
        <div className="grid-2">
          <IdentityCheck
            firstName={profile?.firstName ?? ''}
            lastName={profile?.lastName ?? ''}
            result={idResult}
            onResult={(r) => {
              setIdResult(r);
              if (r.result === 'PASSED' && !passed.includes(r.type)) setPassed([...passed, r.type]);
            }}
          />
          <DocumentUpload documents={documents} onUploaded={(d) => setDocuments([...documents, d])} />
          <Card title="3 · Submit for review" className="span-2">
            <p>
              <code>POST /api/v1/kyc/submit</code> needs a <strong>passed</strong> BVN or NIN check and at least one
              document, otherwise it returns <code>409 KYC_INCOMPLETE</code> listing what's missing.
            </p>
            <ul className="checklist compact">
              <li className={passed.length ? '' : 'pending'}>
                <span className="check" aria-hidden>
                  {passed.length ? '✓' : '·'}
                </span>
                {passed.length ? `${passed.join(' and ')} verified` : 'BVN or NIN verified'}{' '}
                {!passed.length && idResult && <span className="muted">(last check: {humanize(idResult.result)})</span>}
              </li>
              <li className={documents.length ? '' : 'pending'}>
                <span className="check" aria-hidden>
                  {documents.length ? '✓' : '·'}
                </span>
                Identity document uploaded {documents.length > 0 && <span className="muted">({documents.length})</span>}
              </li>
            </ul>
            <button className="btn btn-primary" disabled={busy} onClick={() => run(() => api('/api/v1/kyc/submit', { method: 'POST' }))}>
              Submit for review
            </button>
          </Card>
        </div>
      )}

      {(status === 'SUBMITTED' || status === 'UNDER_REVIEW') && (
        <Card title={status === 'SUBMITTED' ? 'Submitted for review' : 'Under review'}>
          <p>
            Your application is waiting for a compliance reviewer. Customers can't review their own KYC; that needs the{' '}
            <code>KYC_REVIEW</code> permission, and the backend also blocks reviewers from acting on their own profile.
          </p>
          <p>
            Next, test the security rules. After that, switch to the admin account and review this application
            yourself.
          </p>
          <Link className="btn btn-primary" to="/app/security">
            Test the security rules →
          </Link>
        </Card>
      )}

      {status === 'VERIFIED' && (
        <Card title="You're verified" className="success">
          <div className="big-status">
            <StatusBadge status="VERIFIED" />
          </div>
          <p>A reviewer approved your identity verification. You've been through the whole flow, as the customer and as the admin.</p>
          <p className="muted small">Updated {kyc !== 'none' && kyc ? formatDate(kyc.updatedAt) : ''}</p>
          <Link className="btn btn-ghost" to="/">
            Back to the overview
          </Link>
        </Card>
      )}

      {status === 'REJECTED' && kyc !== 'none' && kyc && (
        <Card title="Verification rejected">
          <Notice tone="bad" title="Reviewer's reason">
            {kyc.reviewReason}
          </Notice>
          <p className="muted">Rejection is final in this workflow. The reason is stored and shown to the customer.</p>
        </Card>
      )}
    </>
  );
}

function KycProgress({ status }: { status: KycStatus | null }) {
  const current = status === 'ADDITIONAL_INFO_REQUIRED' ? 'IN_PROGRESS' : status === 'REJECTED' ? 'VERIFIED' : status;
  const index = current ? FLOW.indexOf(current) : -1;

  return (
    <ol className="kyc-flow" aria-label="KYC status">
      {FLOW.map((step, i) => {
        const label = step === 'VERIFIED' && status === 'REJECTED' ? 'REJECTED' : step;
        return (
          <li key={step} className={i < index ? 'past' : i === index ? `current ${status === 'REJECTED' ? 'rejected' : ''}` : ''}>
            <span className="kyc-dot" aria-hidden>
              {i < index ? '✓' : i + 1}
            </span>
            {humanize(label)}
          </li>
        );
      })}
    </ol>
  );
}

function IdentityCheck({
  firstName,
  lastName,
  result,
  onResult,
}: {
  firstName: string;
  lastName: string;
  result: (KycVerification & { type: IdType }) | null;
  onResult: (r: KycVerification & { type: IdType }) => void;
}) {
  const demo = useDemoInfo();
  const simulated = demo?.kycProvider === 'SIMULATED';
  const [type, setType] = useState<IdType>('BVN');
  const [form, setForm] = useState({ number: '', firstName, lastName, dateOfBirth: '' });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const { number, ...person } = form;
      const body = { [type.toLowerCase()]: number, ...person };
      const response = await api<KycVerification>(`/api/v1/kyc/${type.toLowerCase()}`, { body });
      onResult({ ...response, type });
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card title="1 · Check your BVN or NIN">
      <form className="form" onSubmit={submit}>
        <div className="filters" role="tablist" aria-label="Identity number type">
          {(['BVN', 'NIN'] as IdType[]).map((t) => (
            <button
              key={t}
              type="button"
              role="tab"
              aria-selected={type === t}
              className={type === t ? 'active' : ''}
              onClick={() => {
                setType(t);
                setForm({ ...form, number: '' });
              }}
            >
              {t === 'BVN' ? 'BVN (Bank Verification Number)' : 'NIN (National ID Number)'}
            </button>
          ))}
        </div>
        <Notice tone={simulated ? 'warn' : 'info'} title={simulated ? 'Simulated identity provider' : undefined}>
          {simulated
            ? 'This demo checks numbers against a built-in test record instead of a real provider, so it never verifies a real person. '
            : 'Checks go to the Dojah sandbox, which only knows mock records. '}
          Use the test {type} <code>{TEST_NUMBERS[type]}</code> with John Doe, born 1990-01-01.{' '}
          <button type="button" className="link" onClick={() => setForm({ number: TEST_NUMBERS[type], ...TEST_PERSON })}>
            Fill in test data
          </button>
          . Please don't enter a real {type}. The backend never stores the number, only the result of the check.
        </Notice>
        <Field label={type}>
          <input
            required
            inputMode="numeric"
            pattern="\d{11}"
            maxLength={11}
            title="11 digits"
            value={form.number}
            onChange={(e) => setForm({ ...form, number: e.target.value })}
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
        {error && <Notice tone="bad">{error}</Notice>}
        {result && (
          <Notice tone={result.result === 'PASSED' ? 'good' : 'warn'} title={`${result.type} result: ${humanize(result.result)}`}>
            {result.reason ?? `The ${result.provider.toLowerCase()} provider matched every field.`}
            <br />
            <span className="small">
              {result.remainingAttempts} failed {result.type} attempt{result.remainingAttempts === 1 ? '' : 's'} left in this window.
              After that the API returns <code>429</code>.
            </span>
          </Notice>
        )}
        <button className="btn btn-secondary" disabled={busy}>
          {busy ? 'Checking…' : `Check ${type}`}
        </button>
      </form>
    </Card>
  );
}

function DocumentUpload({ documents, onUploaded }: { documents: KycDocument[]; onUploaded: (d: KycDocument) => void }) {
  const [type, setType] = useState<KycDocumentType>('NATIONAL_ID');
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function upload(blob: Blob, name: string) {
    setBusy(true);
    setError(null);
    try {
      const form = new FormData();
      form.append('documentType', type);
      form.append('file', blob, name);
      onUploaded(
        await api<KycDocument>('/api/v1/kyc/documents', {
          form,
          formSummary: { documentType: type, file: `${name} (${Math.round(blob.size / 1024)} KB)` },
        }),
      );
      setFile(null);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card title="2 · Upload an identity document">
      <div className="form">
        <Field label="Document type">
          <select value={type} onChange={(e) => setType(e.target.value as KycDocumentType)}>
            {DOCUMENT_TYPES.map((t) => (
              <option key={t} value={t}>
                {humanize(t)}
              </option>
            ))}
          </select>
        </Field>
        <Notice tone="info">
          No need to upload a real ID: generate a sample image instead. The server checks the file's magic bytes (PDF,
          PNG or JPEG, up to 5 MB) and ignores the file name and declared type.
        </Notice>
        <button className="btn btn-secondary" disabled={busy} onClick={async () => upload(await sampleDocument(type), 'sample-document.png')}>
          Generate &amp; upload a sample document
        </button>
        <Field label="…or choose a file" hint="PDF, PNG or JPEG">
          <input type="file" accept=".pdf,.png,.jpg,.jpeg" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
        </Field>
        {file && (
          <button className="btn btn-ghost" disabled={busy} onClick={() => upload(file, file.name)}>
            Upload {file.name}
          </button>
        )}
        {error && <Notice tone="bad">{error}</Notice>}
        {documents.length > 0 && (
          <ul className="doc-list">
            {documents.map((d) => (
              <li key={d.id}>
                <StatusBadge status="PASSED" label="Stored" /> {humanize(d.documentType)} · {formatDate(d.createdAt)}
              </li>
            ))}
          </ul>
        )}
      </div>
    </Card>
  );
}

/** A clearly fake ID card drawn in the browser, as a PNG. */
function sampleDocument(type: KycDocumentType): Promise<Blob> {
  const canvas = document.createElement('canvas');
  canvas.width = 640;
  canvas.height = 400;
  const ctx = canvas.getContext('2d')!;
  ctx.fillStyle = '#ecfdf5';
  ctx.fillRect(0, 0, 640, 400);
  ctx.strokeStyle = '#0f766e';
  ctx.lineWidth = 8;
  ctx.strokeRect(4, 4, 632, 392);
  ctx.fillStyle = '#0f766e';
  ctx.font = 'bold 34px sans-serif';
  ctx.fillText('PAYCORE DEMO', 32, 64);
  ctx.font = '22px sans-serif';
  ctx.fillText(humanize(type), 32, 104);
  ctx.fillStyle = '#99f6e4';
  ctx.fillRect(32, 140, 160, 200);
  ctx.fillStyle = '#134e4a';
  ctx.font = 'bold 44px sans-serif';
  ctx.fillText('SAMPLE', 230, 230);
  ctx.font = '20px sans-serif';
  ctx.fillText('Not a real identity document', 230, 270);
  ctx.fillText(new Date().toISOString().slice(0, 10), 230, 310);
  return new Promise((resolve) => canvas.toBlob((b) => resolve(b!), 'image/png'));
}
