import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api, errorMessage } from '../../api/client';
import { useAuth } from '../../auth/AuthContext';
import { useBanking } from '../../banking';
import { ScreenTitle } from '../../components/Layout';
import { Avatar, formatAccountNumber, formatDate, humanize, Icon, Notice, StatusBadge } from '../../components/ui';

export function Profile() {
  const { profile, logout } = useAuth();
  const { account, kycStatus } = useBanking();
  const [note, setNote] = useState<{ tone: 'good' | 'bad'; text: string } | null>(null);

  async function resend() {
    try {
      await api('/api/v1/customers/me/verification-email', { method: 'POST' });
      setNote({ tone: 'good', text: 'Verification email sent. Check your inbox.' });
    } catch (e) {
      setNote({ tone: 'bad', text: errorMessage(e) });
    }
  }

  if (!profile) return <p className="muted">Loading…</p>;

  const name = `${profile.firstName} ${profile.lastName}`;

  return (
    <>
      <ScreenTitle title="Profile" />

      <section className="panel profile-head">
        <Avatar name={name} size="lg" />
        <div>
          <h2>{name}</h2>
          <p className="muted">{profile.email}</p>
        </div>
      </section>

      {note && <Notice tone={note.tone}>{note.text}</Notice>}

      <section className="panel">
        <ul className="settings-list">
          <li>
            <Icon name="user" />
            <div>
              <strong>Email</strong>
              <span className="muted small">{profile.email}</span>
            </div>
            {profile.emailVerified ? (
              <StatusBadge status="VERIFIED" />
            ) : (
              <button className="btn btn-ghost btn-sm" onClick={resend}>
                Verify
              </button>
            )}
          </li>
          <li>
            <Icon name="idcard" />
            <div>
              <strong>Identity verification</strong>
              <span className="muted small">BVN or NIN, and an ID document</span>
            </div>
            <Link to="/app/kyc" className="row-link">
              {kycStatus && kycStatus !== 'NONE' ? <StatusBadge status={kycStatus} /> : 'Start'} ›
            </Link>
          </li>
          {account && (
            <li>
              <Icon name="bank" />
              <div>
                <strong>Naira account</strong>
                <span className="muted small">
                  {formatAccountNumber(account.accountNumber)} · opened {formatDate(account.createdAt)}
                </span>
              </div>
              <StatusBadge status={account.status} />
            </li>
          )}
          <li>
            <Icon name="shield" />
            <div>
              <strong>Security checks</strong>
              <span className="muted small">See the API refuse what you are not allowed to do</span>
            </div>
            <Link to="/app/security" className="row-link">
              Open ›
            </Link>
          </li>
          <li>
            <Icon name="user" />
            <div>
              <strong>Phone</strong>
              <span className="muted small">{profile.phoneNumber ?? 'Not added'}</span>
            </div>
            <span className="muted small">{humanize(profile.status)}</span>
          </li>
        </ul>
      </section>

      <button className="btn btn-ghost btn-block" onClick={() => void logout('/login')}>
        <Icon name="logout" /> Sign out
      </button>
    </>
  );
}
