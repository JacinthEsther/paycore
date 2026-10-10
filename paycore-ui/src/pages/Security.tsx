import { useEffect, useState } from 'react';
import { api, ApiError, getSession, type AuthMode } from '../api/client';
import type { Kyc } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { Card, formatDate, HttpStatus, PageHeader } from '../components/ui';

interface Probe {
  id: string;
  title: string;
  explain: string;
  method: string;
  path: (kycId: string | null) => string;
  auth: () => AuthMode;
  expected: (can: (permission: string) => boolean, kycId: string | null) => number;
}

function tamperedToken(): AuthMode {
  const token = getSession()?.accessToken ?? '';
  const i = token.length - 10;
  const swapped = token[i] === 'A' ? 'B' : 'A';
  return { token: token.slice(0, i) + swapped + token.slice(i + 1), label: 'tampered' };
}

const PROBES: Probe[] = [
  {
    id: 'valid',
    title: 'Valid token',
    explain: 'Your real access token. Spring Security checks the HS256 signature and expiry, then resolves you from the sub claim.',
    method: 'GET',
    path: () => '/api/v1/customers/me',
    auth: () => 'bearer',
    expected: () => 200,
  },
  {
    id: 'none',
    title: 'No token',
    explain: 'The same request without an Authorization header. Every endpoint except register, login, refresh and logout needs one.',
    method: 'GET',
    path: () => '/api/v1/customers/me',
    auth: () => 'none',
    expected: () => 401,
  },
  {
    id: 'tampered',
    title: 'Tampered token',
    explain: 'Your token with one character of the signature changed. A forged or edited JWT fails verification.',
    method: 'GET',
    path: () => '/api/v1/customers/me',
    auth: tamperedToken,
    expected: () => 401,
  },
  {
    id: 'rbac',
    title: 'Missing permission',
    explain: 'Listing all customers needs CUSTOMER_READ. The CUSTOMER role only has self-service permissions, so the token is valid but not allowed.',
    method: 'GET',
    path: () => '/api/v1/admin/customers',
    auth: () => 'bearer',
    expected: (can) => (can('CUSTOMER_READ') ? 200 : 403),
  },
  {
    id: 'self-approve',
    title: 'Approve your own KYC',
    explain: 'Needs KYC_REVIEW, which only Compliance has. Even Compliance gets 403 on their own profile, because nobody may review themselves (404 if they have no KYC profile).',
    method: 'POST',
    path: (kycId) => `/api/v1/kyc/${kycId ?? '00000000-0000-7000-8000-000000000000'}/approve`,
    auth: () => 'bearer',
    expected: (can, kycId) => (can('KYC_REVIEW') && !kycId ? 404 : 403),
  },
];

export function Security() {
  const { session, claims, isStaff, can, logout } = useAuth();
  const [kycId, setKycId] = useState<string | null>(null);
  const [results, setResults] = useState<Record<string, number | null>>({});
  const [running, setRunning] = useState(false);

  useEffect(() => {
    api<Kyc>('/api/v1/kyc')
      .then((k) => setKycId(k.id))
      .catch(() => setKycId(null));
  }, []);

  async function runProbe(probe: Probe) {
    let status: number | null;
    try {
      await api(probe.path(kycId), { method: probe.method, auth: probe.auth() });
      status = 200;
    } catch (e) {
      status = e instanceof ApiError ? e.status || null : null;
    }
    setResults((r) => ({ ...r, [probe.id]: status }));
    return status;
  }

  async function runAll() {
    setRunning(true);
    const statuses: (number | null)[] = [];
    for (const probe of PROBES) statuses.push(await runProbe(probe));
    setRunning(false);
  }

  async function switchToCompliance() {
    await logout('/login?as=staff&staff=compliance%40paycore.demo&switched=1');
  }

  return (
    <>
      <PageHeader eyebrow="Security" title="Try to break in">
        The UI hides buttons you can't use, but the backend is what actually enforces access. These requests go straight
        to the API, some with a missing or tampered token or without the right permission. Each one should fail with
        the status shown.
      </PageHeader>

      <div className="grid-2 security">
        <Card
          title="Authentication → RBAC → protected API"
          aside={
            <button className="btn btn-primary btn-sm" onClick={runAll} disabled={running}>
              {running ? 'Running…' : 'Run all'}
            </button>
          }
        >
          <ul className="probes">
            {PROBES.map((probe) => {
              const expected = probe.expected(can, kycId);
              const actual = results[probe.id];
              const ran = probe.id in results;
              return (
                <li key={probe.id} className={ran ? (actual === expected ? 'pass' : 'fail') : ''}>
                  <div className="probe-main">
                    <strong>{probe.title}</strong>
                    <code className="small">
                      {probe.method} {probe.path(kycId).replace(/[0-9a-f-]{36}/, '{kycId}')}
                    </code>
                    <span className="muted small">{probe.explain}</span>
                  </div>
                  <div className="probe-side">
                    <span className="muted small">expect</span> <HttpStatus status={expected} />
                    <span className="muted small">got</span> <HttpStatus status={ran ? actual : null} />
                    <button className="btn btn-ghost btn-sm" onClick={() => runProbe(probe)}>
                      Send
                    </button>
                  </div>
                </li>
              );
            })}
          </ul>
        </Card>

        <Card title="What your access token says">
          <p className="muted small">
            Decoded here for display only. Only the server can check the signature. Claims are limited to what
            authorization needs; no profile or KYC data goes in the token.
          </p>
          <dl className="kv">
            <dt>sub</dt>
            <dd>
              <code>{claims?.sub}</code>
            </dd>
            <dt>roles</dt>
            <dd>
              {claims?.roles.map((r) => (
                <span key={r} className="chip">
                  {r}
                </span>
              ))}
            </dd>
            <dt>permissions</dt>
            <dd>
              {claims?.permissions.map((p) => (
                <span key={p} className="chip chip-soft">
                  {p}
                </span>
              ))}
            </dd>
            <dt>expires</dt>
            <dd>{claims ? formatDate(new Date(claims.exp * 1000).toISOString()) : '—'} (15 min, then silently refreshed)</dd>
            <dt>session</dt>
            <dd>
              <code>{session?.sessionId}</code>
              <br />
              <span className="muted small">until {formatDate(session?.sessionExpiresAt)}</span>
            </dd>
          </dl>
        </Card>
      </div>

      {!isStaff && (
        <section id="switch" className="switch-card">
          <div>
            <p className="eyebrow">End of the customer phase</p>
            <h2>Do you want to see the other side?</h2>
            <p>
              I'll sign you out, which revokes this login session on the server, and take you to the staff sign-in as the
              Compliance Officer. There you can pick up the KYC application you just submitted, look at the BVN result
              and your document, and approve it or send it back.
            </p>
          </div>
          <button className="btn btn-primary btn-lg" onClick={switchToCompliance}>
            Sign out &amp; continue as Compliance →
          </button>
        </section>
      )}
    </>
  );
}
