import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, ApiError } from '../api/client';
import type { KycStatus } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { Card, formatDate, PageHeader, StatusBadge } from '../components/ui';
import { markDone, useJourney } from '../journey';

export function Dashboard() {
  const { profile, session, claims, isAdmin } = useAuth();
  const { done } = useJourney();
  const [kycStatus, setKycStatus] = useState<KycStatus | 'NONE' | null>(null);

  useEffect(() => {
    if (!isAdmin) markDone('dashboard');
    api<{ status: KycStatus }>('/api/v1/kyc/status')
      .then((r) => setKycStatus(r.status))
      .catch((e) => setKycStatus(e instanceof ApiError && e.status === 404 ? 'NONE' : null));
  }, [isAdmin]);

  const nextStep = isAdmin
    ? { to: '/admin', label: 'Open the KYC review queue' }
    : !done.includes('kyc')
      ? { to: '/app/kyc', label: 'Verify your identity (KYC)' }
      : !done.includes('security')
        ? { to: '/app/security', label: 'Test the security rules' }
        : { to: '/app/security#switch', label: 'Switch to the admin view' };

  return (
    <>
      <PageHeader eyebrow={isAdmin ? 'Admin' : 'Step 3 · Customer'} title={`Welcome back${profile ? `, ${profile.firstName}` : ''}`}>
        Everything on this page comes from <code>GET /api/v1/customers/me</code> and <code>GET /api/v1/kyc/status</code>.
        The backend works out who "me" is from the access token alone; the UI never sends your customer id.
      </PageHeader>

      <div className="tiles">
        <Card title="Account status">
          {profile ? <StatusBadge status={profile.status} /> : <span className="muted">Loading…</span>}
          <p className="muted small">
            {profile?.status === 'PENDING_VERIFICATION'
              ? 'New customers stay pending until their email is verified (next up on the backend).'
              : 'Customer lifecycle: pending → active → suspended / closed.'}
          </p>
        </Card>

        <Card title="KYC">
          {kycStatus === null ? (
            <span className="muted">Loading…</span>
          ) : kycStatus === 'NONE' ? (
            <StatusBadge status="NOT_STARTED" label="Not started" />
          ) : (
            <StatusBadge status={kycStatus} />
          )}
          <p className="muted small">
            <Link to="/app/kyc">Open identity verification →</Link>
          </p>
        </Card>

        <Card title="Security">
          <StatusBadge status="ACTIVE" label="Authenticated" />
          <p className="muted small">
            Roles: {claims?.roles.join(', ') || '—'}
            <br />
            Session expires {formatDate(session?.sessionExpiresAt)}
          </p>
        </Card>

        <Card title="Account" className="tile-soon">
          <span className="badge badge-neutral">Coming soon</span>
          <p className="muted small">Accounts, ledger, transactions and payments are what I'm building next.</p>
        </Card>
      </div>

      {profile && (
        <Card title="Your profile">
          <dl className="kv kv-wide">
            <dt>Customer id</dt>
            <dd>
              <code>{profile.id}</code> <span className="muted small">(UUIDv7)</span>
            </dd>
            <dt>Name</dt>
            <dd>
              {profile.firstName} {profile.lastName}
            </dd>
            <dt>Email</dt>
            <dd>
              {profile.email} {profile.emailVerified ? <StatusBadge status="VERIFIED" /> : <span className="muted small">(unverified)</span>}
            </dd>
            <dt>Phone</dt>
            <dd>
              {profile.phoneNumber} <span className="muted small">(stored as E.164)</span>
            </dd>
            <dt>Joined</dt>
            <dd>{formatDate(profile.createdAt)}</dd>
          </dl>
        </Card>
      )}

      <div className="next-step">
        <span className="muted">Next:</span>
        <Link to={nextStep.to} className="btn btn-primary">
          {nextStep.label} →
        </Link>
      </div>
    </>
  );
}
