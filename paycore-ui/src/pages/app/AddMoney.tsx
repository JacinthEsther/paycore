import { useEffect, useState, type FormEvent } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { api, ApiError, errorMessage } from '../../api/client';
import type { Account, FundingAllowance, TestBankAccount, Transaction } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { refreshBanking, useBanking } from '../../banking';
import { ScreenTitle } from '../../components/Layout';
import { CopyButton, Field, formatAccountNumber, formatMoney, Icon, newIdempotencyKey, Notice } from '../../components/ui';
import { markDone } from '../../journey';

type Tab = 'transfer' | 'card';

export function AddMoney() {
  const { account, loading } = useBanking();
  const location = useLocation();
  const [tab, setTab] = useState<Tab>('transfer');

  useEffect(() => {
    if (location.hash === '#receive') setTab('transfer');
  }, [location.hash]);

  if (loading) return <p className="muted">Loading…</p>;

  if (!account || account.status !== 'ACTIVE') {
    return (
      <>
        <ScreenTitle title="Add money" />
        <Notice tone="warn">
          Your account needs to be active before it can receive money. <Link to="/app">See what is left to do</Link>.
        </Notice>
      </>
    );
  }

  return (
    <>
      <ScreenTitle title="Add money">Money only reaches your account when another bank or a card processor confirms it.</ScreenTitle>

      <div className="segmented" role="tablist">
        <button role="tab" aria-selected={tab === 'transfer'} className={tab === 'transfer' ? 'active' : ''} onClick={() => setTab('transfer')}>
          <Icon name="bank" size={18} /> Bank transfer
        </button>
        <button role="tab" aria-selected={tab === 'card'} className={tab === 'card' ? 'active' : ''} onClick={() => setTab('card')}>
          <Icon name="card" size={18} /> Card
        </button>
      </div>

      {tab === 'transfer' ? <BankTransferIn account={account} /> : <CardTopUp account={account} />}
    </>
  );
}

function BankTransferIn({ account }: { account: Account }) {
  const { profile } = useAuth();
  const [testBank, setTestBank] = useState<TestBankAccount | null>(null);
  const [unavailable, setUnavailable] = useState(false);
  const [amount, setAmount] = useState('');
  const [narration, setNarration] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [received, setReceived] = useState<Transaction | null>(null);

  const load = () =>
    api<TestBankAccount>(`/api/v1/simulator/test-bank/account?accountId=${account.id}`)
      .then(setTestBank)
      .catch(() => setUnavailable(true));

  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [account.id]);

  async function send(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setReceived(null);
    try {
      const credit = await api<Transaction>('/api/v1/simulator/test-bank/transfers', {
        body: { destinationAccountNumber: account.accountNumber, amount: Number(amount), narration: narration.trim() || null },
      });
      setReceived(credit);
      setAmount('');
      setNarration('');
      markDone('receive');
      refreshBanking();
      void load();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="stack">
      <section className="panel account-details" id="receive">
        <h2>Your account details</h2>
        <p className="muted small">Anyone can send money to this account from any bank.</p>
        <dl className="details-list">
          <dt>Bank</dt>
          <dd>PayCore</dd>
          <dt>Account number</dt>
          <dd className="with-copy">
            <strong className="mono">{formatAccountNumber(account.accountNumber)}</strong>
            <CopyButton text={account.accountNumber} />
          </dd>
          <dt>Account name</dt>
          <dd>{profile ? `${profile.firstName} ${profile.lastName}` : '—'}</dd>
        </dl>
      </section>

      <section className="panel simulator">
        <header className="panel-head">
          <h2>Send from Test Bank</h2>
          <span className="chip chip-info">Simulated bank</span>
        </header>

        {unavailable ? (
          <Notice tone="warn">Transfers from other banks are not enabled on this server.</Notice>
        ) : !testBank ? (
          <p className="muted">Loading…</p>
        ) : (
          <>
            <p className="muted small">
              Pretend you are in your other bank's app. Test Bank sends the money over the interbank rail with a NIP
              session id; PayCore credits you when the rail reports it, exactly as it would for a real bank.
            </p>
            <div className="from-account">
              <span className="from-bank">
                <Icon name="bank" />
              </span>
              <div>
                <strong>{testBank.accountName}</strong>
                <span className="muted small">
                  Test Bank · {formatAccountNumber(testBank.accountNumber)}
                </span>
              </div>
            </div>

            <form className="form" onSubmit={send}>
              <Field
                label="Amount"
                hint={`Test Bank can still send ${formatMoney(testBank.remaining, testBank.currency)} to this account (its money is pretend, so it is capped).`}
              >
                <div className="amount-input">
                  <span>₦</span>
                  <input value={amount} type="number" min="0.01" step="0.01" placeholder="0.00" required onChange={(e) => setAmount(e.target.value)} />
                </div>
              </Field>
              <Field label="Narration (optional)">
                <input value={narration} maxLength={100} placeholder="e.g. Savings" onChange={(e) => setNarration(e.target.value)} />
              </Field>
              {error && <Notice tone="bad">{error}</Notice>}
              <button className="btn btn-primary btn-block" disabled={busy || testBank.remaining <= 0}>
                {busy ? 'Sending…' : `Send to PayCore ${formatAccountNumber(account.accountNumber)}`}
              </button>
            </form>

            {received && (
              <Notice tone="good" title={`${formatMoney(received.amount, received.currency)} received`}>
                From {received.counterparty?.name} · Test Bank. Reference <code>{received.reference}</code>.{' '}
                <Link to={`/app/transactions/${received.id}`}>View receipt</Link>
              </Notice>
            )}
          </>
        )}
      </section>
    </div>
  );
}

function CardTopUp({ account }: { account: Account }) {
  const [allowance, setAllowance] = useState<FundingAllowance | null>(null);
  const [amount, setAmount] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<{ message: string; reference?: string } | null>(null);
  const [funded, setFunded] = useState<Transaction | null>(null);

  const load = () =>
    api<FundingAllowance>(`/api/v1/ledger/accounts/${account.id}/funding`)
      .then(setAllowance)
      .catch(() => setAllowance(null));

  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [account.id]);

  async function pay(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setFunded(null);
    try {
      const result = await api<Transaction>(`/api/v1/ledger/accounts/${account.id}/fundings`, {
        body: { amount: Number(amount), currency: account.currency, idempotencyKey: newIdempotencyKey('topup') },
      });
      setFunded(result);
      setAmount('');
      refreshBanking();
      void load();
    } catch (err) {
      setError({ message: errorMessage(err), reference: err instanceof ApiError ? err.body.providerReference : undefined });
    } finally {
      setBusy(false);
    }
  }

  if (!allowance) return <p className="muted">Loading…</p>;

  if (!allowance.enabled) {
    return <Notice tone="warn">Card top-ups are not enabled on this server.</Notice>;
  }

  return (
    <section className="panel">
      <header className="panel-head">
        <h2>Top up with a card</h2>
        {allowance.provider === 'SIMULATED' && <span className="chip chip-info">Test card</span>}
      </header>
      <div className="test-card" aria-hidden>
        <span>PayCore Test Card</span>
        <span className="mono">4242 •••• •••• 4242</span>
      </div>
      <form className="form" onSubmit={pay}>
        <Field
          label="Amount"
          hint={`${formatMoney(allowance.remaining, allowance.currency)} of your ${formatMoney(allowance.limit, allowance.currency)} card limit left. Amounts ending in .99 are declined.`}
        >
          <div className="amount-input">
            <span>₦</span>
            <input value={amount} type="number" min="0.01" step="0.01" placeholder="0.00" required onChange={(e) => setAmount(e.target.value)} />
          </div>
        </Field>
        {error && (
          <Notice tone="bad">
            {error.message}
            {error.reference && (
              <>
                {' '}
                Payment reference <code>{error.reference}</code>. Nothing was charged to your account.
              </>
            )}
          </Notice>
        )}
        <button className="btn btn-primary btn-block" disabled={busy || allowance.remaining <= 0}>
          {busy ? 'Paying…' : 'Pay with test card'}
        </button>
      </form>
      {funded && (
        <Notice tone="good" title={`${formatMoney(funded.amount, funded.currency)} added`}>
          Payment reference <code>{funded.providerReference}</code>.{' '}
          <Link to={`/app/transactions/${funded.id}`}>View receipt</Link>
        </Notice>
      )}
    </section>
  );
}
