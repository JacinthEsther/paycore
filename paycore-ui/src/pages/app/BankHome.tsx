import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, errorMessage } from '../../api/client';
import type { Account } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { refreshBanking, useBanking, useStatement } from '../../banking';
import { TransactionList } from '../../components/TransactionList';
import { CopyButton, formatAccountNumber, formatMoney, humanize, Icon, Notice } from '../../components/ui';
import { markDone, useJourney } from '../../journey';

const HIDE_KEY = 'paycore.hideBalance';

export function BankHome() {
  const { account, balance, kycStatus, loading, error } = useBanking();
  const { done } = useJourney();

  useEffect(() => {
    if (account) markDone('account');
    if (account?.status === 'ACTIVE' && done.includes('activate')) markDone('verified');
  }, [account, done]);

  if (loading) return <p className="muted">Loading…</p>;
  if (error) return <Notice tone="bad">{error}</Notice>;

  if (!account || account.status === 'PENDING') {
    return <Onboarding account={account} kycStatus={kycStatus} />;
  }

  return (
    <div className="home-grid">
      <BalanceCard account={account} balance={balance?.balance ?? null} />

      <nav className="quick-actions" aria-label="Quick actions">
        <Link to="/app/transfer" className="quick-action">
          <span className="qa-icon">
            <Icon name="send" />
          </span>
          Transfer
        </Link>
        <Link to="/app/add-money" className="quick-action">
          <span className="qa-icon">
            <Icon name="plus" />
          </span>
          Add money
        </Link>
        <Link to="/app/add-money#receive" className="quick-action">
          <span className="qa-icon">
            <Icon name="receive" />
          </span>
          Receive
        </Link>
        <Link to="/app/transactions" className="quick-action">
          <span className="qa-icon">
            <Icon name="list" />
          </span>
          History
        </Link>
      </nav>

      {account.status === 'FROZEN' && (
        <Notice tone="warn" title="Your account is frozen.">
          PayCore staff froze it. You can still see your history, but no money can move until it is unfrozen.
        </Notice>
      )}

      <RecentActivity account={account} />
    </div>
  );
}

function BalanceCard({ account, balance }: { account: Account; balance: number | null }) {
  const { profile } = useAuth();
  const [hidden, setHidden] = useState(() => {
    try {
      return localStorage.getItem(HIDE_KEY) === 'true';
    } catch {
      return false;
    }
  });

  function toggle() {
    setHidden((h) => {
      try {
        localStorage.setItem(HIDE_KEY, String(!h));
      } catch {
        // Not remembered; fine.
      }
      return !h;
    });
  }

  return (
    <section className="balance-card" aria-label="Balance">
      <div className="balance-top">
        <span className="balance-label">Available balance</span>
        <button className="balance-eye" onClick={toggle} aria-label={hidden ? 'Show balance' : 'Hide balance'}>
          <Icon name={hidden ? 'eye' : 'eyeOff'} size={18} />
        </button>
      </div>
      <p className="balance-amount">{hidden ? '₦ • • • • •' : balance === null ? '…' : formatMoney(balance, account.currency)}</p>
      <div className="balance-account">
        <div>
          <span className="balance-label">{profile ? `${profile.firstName} ${profile.lastName}` : 'Account'}</span>
          <span className="balance-number">PayCore · {formatAccountNumber(account.accountNumber)}</span>
        </div>
        <CopyButton text={account.accountNumber} label="Copy" />
      </div>
    </section>
  );
}

function RecentActivity({ account }: { account: Account }) {
  const { statement, error } = useStatement(account.id);

  return (
    <section className="panel">
      <header className="panel-head">
        <h2>Recent activity</h2>
        <Link to="/app/transactions">See all</Link>
      </header>
      {error && <Notice tone="bad">{error}</Notice>}
      {statement ? (
        <TransactionList
          lines={statement.lines}
          currency={statement.currency}
          limit={6}
          empty="No transactions this month yet. Add money to get started."
        />
      ) : (
        !error && <p className="muted">Loading…</p>
      )}
    </section>
  );
}

/**
 * Before the account can be used: verify identity, open the account, wait
 * for compliance to activate it.
 */
function Onboarding({ account, kycStatus }: { account: Account | null; kycStatus: string | null }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const kycDone = kycStatus === 'VERIFIED';
  const kycSubmitted = kycStatus === 'SUBMITTED' || kycStatus === 'UNDER_REVIEW' || kycDone;

  async function open() {
    setBusy(true);
    setError(null);
    try {
      await api('/api/v1/accounts', { body: { type: 'PERSONAL', currency: 'NGN' } });
      markDone('account');
      refreshBanking();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="onboarding">
      <section className="hero-card">
        <h1>Let's get your account ready</h1>
        <p>Three steps, the same ones a licensed bank takes before your account can hold money.</p>
      </section>

      <ol className="steps">
        <li className={kycDone ? 'step done' : kycSubmitted ? 'step waiting' : 'step current'}>
          <span className="step-mark">{kycDone ? <Icon name="check" size={16} /> : 1}</span>
          <div>
            <strong>Verify your identity</strong>
            <p className="muted small">
              BVN or NIN check plus an ID document.{' '}
              {kycStatus && kycStatus !== 'NONE' && <>Status: {humanize(kycStatus)}.</>}
            </p>
            {!kycSubmitted && (
              <Link className="btn btn-primary btn-sm" to="/app/kyc">
                Start verification
              </Link>
            )}
            {kycSubmitted && !kycDone && <p className="small">Compliance is reviewing it.</p>}
          </div>
        </li>

        <li className={account ? 'step done' : 'step current'}>
          <span className="step-mark">{account ? <Icon name="check" size={16} /> : 2}</span>
          <div>
            <strong>Open a naira account</strong>
            <p className="muted small">You get a 10-digit NUBAN account number.</p>
            {account ? (
              <p className="small">
                Account <strong>{formatAccountNumber(account.accountNumber)}</strong> opened.
              </p>
            ) : (
              <button className="btn btn-primary btn-sm" onClick={open} disabled={busy}>
                {busy ? 'Opening…' : 'Open account'}
              </button>
            )}
            {error && <Notice tone="bad">{error}</Notice>}
          </div>
        </li>

        <li className={account && kycDone ? 'step waiting' : 'step'}>
          <span className="step-mark">3</span>
          <div>
            <strong>Activation</strong>
            <p className="muted small">
              Compliance activates your account once your identity is verified. Then you can receive and send money.
            </p>
            {account && (
              <Link className="small" to="/login?as=staff">
                Demo: sign in as the compliance admin to do it →
              </Link>
            )}
          </div>
        </li>
      </ol>
    </div>
  );
}
