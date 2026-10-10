import { useEffect, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { api, ApiError, errorMessage } from '../../api/client';
import { useDemoInfo } from '../../api/demo';
import type { Account, Bank, NameEnquiry, OutboundTransfer, Payment, TestBankAccount, Transaction } from '../../api/types';
import { refreshBanking, useBanking } from '../../banking';
import { ScreenTitle } from '../../components/Layout';
import { PinInput } from '../../components/PinInput';
import { Avatar, Field, formatAccountNumber, formatMoney, Icon, newIdempotencyKey, Notice } from '../../components/ui';
import { markDone } from '../../journey';
import { useTransactionSecurity } from '../../security';

const PAYCORE = '100999';

interface Draft {
  bank: Bank;
  accountNumber: string;
  accountName: string;
  amount: string;
  narration: string;
  key: string;
}

type Outcome =
  | { kind: 'internal'; transaction: Transaction }
  | { kind: 'external'; transfer: OutboundTransfer };

export function Transfer() {
  const { account, balance, loading } = useBanking();
  const [draft, setDraft] = useState<Draft | null>(null);
  const [outcome, setOutcome] = useState<Outcome | null>(null);
  const [pin, setPin] = useState('');

  if (loading) return <p className="muted">Loading…</p>;

  if (!account || account.status !== 'ACTIVE' || !balance) {
    return (
      <>
        <ScreenTitle title="Transfer" />
        <Notice tone="warn">
          Your account needs to be active before you can send money. <Link to="/app">See what is left to do</Link>.
        </Notice>
      </>
    );
  }

  if (outcome && draft) {
    return (
      <Receipt
        draft={draft}
        outcome={outcome}
        account={account}
        pin={pin}
        onAnother={() => {
          setDraft(null);
          setOutcome(null);
          setPin('');
        }}
      />
    );
  }

  if (draft) {
    return (
      <Review
        draft={draft}
        account={account}
        onBack={() => setDraft(null)}
        onSent={(sent, usedPin) => {
          setPin(usedPin);
          setOutcome(sent);
        }}
      />
    );
  }

  return <Details account={account} available={balance.balance} onContinue={setDraft} />;
}

function Details({
  account,
  available,
  onContinue,
}: {
  account: Account;
  available: number;
  onContinue: (draft: Draft) => void;
}) {
  const demo = useDemoInfo();
  const [banks, setBanks] = useState<Bank[]>([]);
  const [bankCode, setBankCode] = useState(PAYCORE);
  const [accountNumber, setAccountNumber] = useState('');
  const [enquiry, setEnquiry] = useState<NameEnquiry | null>(null);
  const [enquiryError, setEnquiryError] = useState<string | null>(null);
  const [checking, setChecking] = useState(false);
  const [amount, setAmount] = useState('');
  const [narration, setNarration] = useState('');
  const [ownTestBank, setOwnTestBank] = useState<TestBankAccount | null>(null);

  useEffect(() => {
    api<Bank[]>('/api/v1/banks').then(setBanks).catch(() => setBanks([{ code: PAYCORE, name: 'PayCore' }]));
    api<TestBankAccount>(`/api/v1/simulator/test-bank/account?accountId=${account.id}`)
      .then(setOwnTestBank)
      .catch(() => setOwnTestBank(null));
  }, [account.id]);

  useEffect(() => {
    setEnquiry(null);
    setEnquiryError(null);
    if (!/^\d{10}$/.test(accountNumber)) return;

    if (bankCode === PAYCORE && accountNumber === account.accountNumber) {
      setEnquiryError('That is your own account.');
      return;
    }

    let cancelled = false;
    setChecking(true);
    api<NameEnquiry>('/api/v1/banks/name-enquiry', { body: { bankCode, accountNumber } })
      .then((result) => !cancelled && setEnquiry(result))
      .catch((e) => !cancelled && setEnquiryError(errorMessage(e)))
      .finally(() => !cancelled && setChecking(false));
    return () => {
      cancelled = true;
    };
  }, [bankCode, accountNumber, account.accountNumber]);

  const bank = banks.find((b) => b.code === bankCode) ?? { code: bankCode, name: bankCode === PAYCORE ? 'PayCore' : '' };
  const amountValue = Number(amount);
  const tooMuch = amountValue > available;

  function submit(e: FormEvent) {
    e.preventDefault();
    if (!enquiry || tooMuch) return;
    onContinue({
      bank,
      accountNumber,
      accountName: enquiry.accountName,
      amount,
      narration: narration.trim(),
      key: newIdempotencyKey('transfer'),
    });
  }

  function pick(code: string, number: string) {
    setBankCode(code);
    setAccountNumber(number);
  }

  return (
    <>
      <ScreenTitle title="Transfer">Send money to any PayCore account or to another bank.</ScreenTitle>

      <form className="panel form" onSubmit={submit}>
        <div className="bank-picker" role="radiogroup" aria-label="Bank">
          {banks.map((b) => (
            <button
              type="button"
              key={b.code}
              role="radio"
              aria-checked={b.code === bankCode}
              className={`bank-option ${b.code === bankCode ? 'selected' : ''}`}
              onClick={() => setBankCode(b.code)}
            >
              <Icon name="bank" />
              {b.name}
            </button>
          ))}
        </div>

        <Field
          label="Account number"
          hint={
            <span className="shortcuts">
              {bankCode === PAYCORE && demo?.recipientAccountNumber && (
                <button type="button" className="chip-btn" onClick={() => pick(PAYCORE, demo.recipientAccountNumber!)}>
                  {demo.recipientName}
                </button>
              )}
              {bankCode !== PAYCORE && ownTestBank && (
                <button type="button" className="chip-btn" onClick={() => pick(ownTestBank.bankCode, ownTestBank.accountNumber)}>
                  My Test Bank account
                </button>
              )}
            </span>
          }
        >
          <input
            value={accountNumber}
            inputMode="numeric"
            maxLength={10}
            placeholder="10-digit account number"
            required
            onChange={(e) => setAccountNumber(e.target.value.replace(/\D/g, ''))}
          />
        </Field>

        {checking && <p className="muted small">Checking account name…</p>}
        {enquiry && (
          <div className="beneficiary">
            <Avatar name={enquiry.accountName} size="sm" />
            <div>
              <strong>{enquiry.accountName}</strong>
              <span className="muted small">
                {enquiry.bankName} · {formatAccountNumber(enquiry.accountNumber)}
              </span>
            </div>
            <Icon name="check" />
          </div>
        )}
        {enquiryError && <Notice tone="bad">{enquiryError}</Notice>}

        <Field label="Amount" hint={`Available: ${formatMoney(available, account.currency)}`}>
          <div className="amount-input">
            <span>₦</span>
            <input
              value={amount}
              type="number"
              min="0.01"
              step="0.01"
              placeholder="0.00"
              required
              onChange={(e) => setAmount(e.target.value)}
            />
          </div>
        </Field>
        {tooMuch && <Notice tone="warn">That is more than your available balance.</Notice>}

        <Field label="Narration (optional)" hint="Shown on both statements.">
          <input value={narration} maxLength={100} onChange={(e) => setNarration(e.target.value)} placeholder="e.g. Rent" />
        </Field>

        {bankCode !== PAYCORE && (
          <p className="muted small">
            Test Bank is simulated, and the kobo pick what happens: <code>.99</code> is rejected and reversed at once;{' '}
            <code>.98</code> is paid but the answer is lost, so it shows as processing until PayCore's status check
            confirms it; <code>.97</code> never reaches the bank, so the check reverses it; <code>.96</code> can never be
            confirmed, so operations resolve it under maker-checker; <code>.93</code> is accepted but returned by the bank
            later, which only reconciliation finds.
          </p>
        )}

        <button className="btn btn-primary btn-block" disabled={!enquiry || tooMuch || !amount}>
          Continue
        </button>
      </form>
    </>
  );
}

function Review({
  draft,
  account,
  onBack,
  onSent,
}: {
  draft: Draft;
  account: Account;
  onBack: () => void;
  onSent: (outcome: Outcome, pin: string) => void;
}) {
  const security = useTransactionSecurity();
  const [pin, setPin] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const internal = draft.bank.code === PAYCORE;
  const headers = { 'X-Transaction-PIN': pin };

  async function send() {
    setBusy(true);
    setError(null);
    try {
      if (internal) {
        const transaction = await api<Transaction>(`/api/v1/ledger/accounts/${account.id}/transfers`, {
          body: {
            destinationAccountNumber: draft.accountNumber,
            amount: Number(draft.amount),
            currency: account.currency,
            idempotencyKey: draft.key,
            description: draft.narration || null,
          },
          headers,
        });
        markDone('transfer');
        onSent({ kind: 'internal', transaction }, pin);
      } else {
        const transfer = await api<OutboundTransfer>(`/api/v1/ledger/accounts/${account.id}/bank-transfers`, {
          body: {
            bankCode: draft.bank.code,
            accountNumber: draft.accountNumber,
            amount: Number(draft.amount),
            currency: account.currency,
            narration: draft.narration || null,
            idempotencyKey: draft.key,
          },
          headers,
        });
        markDone('transfer-out');
        onSent({ kind: 'external', transfer }, pin);
      }
      refreshBanking();
    } catch (e) {
      setError(errorMessage(e));
      if (e instanceof ApiError && ['INVALID_PIN', 'PIN_LOCKED'].includes(e.body.error ?? '')) {
        setPin('');
        void security.reload();
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <ScreenTitle title="Confirm transfer" />
      <section className="panel review">
        <p className="review-amount">{formatMoney(Number(draft.amount), account.currency)}</p>
        <dl className="review-list">
          <dt>To</dt>
          <dd>
            <strong>{draft.accountName}</strong>
            <span className="muted small">
              {draft.bank.name} · {formatAccountNumber(draft.accountNumber)}
            </span>
          </dd>
          <dt>From</dt>
          <dd>PayCore · {formatAccountNumber(account.accountNumber)}</dd>
          {draft.narration && (
            <>
              <dt>Narration</dt>
              <dd>{draft.narration}</dd>
            </>
          )}
          <dt>Fee</dt>
          <dd>₦0.00</dd>
        </dl>
        {!security.loading && !security.ready && (
          <Notice tone="warn">
            {security.device?.status !== 'TRUSTED'
              ? 'Confirm this device first.'
              : security.pin?.locked
                ? 'Your PIN is locked.'
                : 'Set a transaction PIN first.'}{' '}
            <Link to="/app/devices">PIN & devices ›</Link>
          </Notice>
        )}
        {security.ready && <PinInput value={pin} onChange={setPin} autoFocus />}
        {error && <Notice tone="bad">{error}</Notice>}
        <div className="actions">
          <button className="btn btn-ghost" onClick={onBack} disabled={busy}>
            Edit
          </button>
          <button className="btn btn-primary" onClick={send} disabled={busy || !security.ready || pin.length !== 4}>
            {busy ? 'Sending…' : 'Send money'}
          </button>
        </div>
      </section>
    </>
  );
}

function Receipt({
  draft,
  outcome,
  account,
  pin,
  onAnother,
}: {
  draft: Draft;
  outcome: Outcome;
  account: Account;
  pin: string;
  onAnother: () => void;
}) {
  const transaction = outcome.kind === 'internal' ? outcome.transaction : outcome.transfer;
  const failed = outcome.kind === 'external' && outcome.transfer.transferStatus === 'FAILED';
  const pending = outcome.kind === 'external' && outcome.transfer.transferStatus === 'PENDING';
  const [retryNote, setRetryNote] = useState<string | null>(null);
  const [checked, setChecked] = useState<Payment | null>(null);

  async function checkStatus() {
    if (outcome.kind !== 'external') return;
    try {
      setChecked(await api<Payment>(`/api/v1/payments/${outcome.transfer.paymentId}`));
      refreshBanking();
    } catch (e) {
      setRetryNote(errorMessage(e));
    }
  }

  async function resend() {
    try {
      const again =
        outcome.kind === 'internal'
          ? await api<Transaction>(`/api/v1/ledger/accounts/${account.id}/transfers`, {
              body: {
                destinationAccountNumber: draft.accountNumber,
                amount: Number(draft.amount),
                currency: account.currency,
                idempotencyKey: draft.key,
                description: draft.narration || null,
              },
              headers: { 'X-Transaction-PIN': pin },
            })
          : await api<OutboundTransfer>(`/api/v1/ledger/accounts/${account.id}/bank-transfers`, {
              body: {
                bankCode: draft.bank.code,
                accountNumber: draft.accountNumber,
                amount: Number(draft.amount),
                currency: account.currency,
                narration: draft.narration || null,
                idempotencyKey: draft.key,
              },
              headers: { 'X-Transaction-PIN': pin },
            });
      setRetryNote(
        again.id === transaction.id
          ? `Same transaction back (${again.reference}). The idempotency key was recognised, so no money moved twice.`
          : `A different transaction came back (${again.reference}). That should not happen.`,
      );
      refreshBanking();
    } catch (e) {
      setRetryNote(errorMessage(e));
    }
  }

  return (
    <section className="panel receipt">
      <div className={`receipt-mark ${failed ? 'receipt-failed' : 'receipt-ok'}`}>
        <Icon name={failed ? 'undo' : 'check'} size={32} />
      </div>
      <h1>{failed ? 'Transfer failed' : pending ? 'Transfer processing' : 'Transfer successful'}</h1>
      <p className="review-amount">{formatMoney(transaction.amount, transaction.currency)}</p>
      <p className="muted">
        {failed
          ? `${draft.bank.name} rejected it, so PayCore reversed the debit. Your money is back in your account.`
          : `Sent to ${draft.accountName} · ${draft.bank.name}`}
      </p>
      {failed && outcome.kind === 'external' && outcome.transfer.failureReason && (
        <Notice tone="warn">{outcome.transfer.failureReason}</Notice>
      )}
      {pending && (
        <Notice tone="warn" title="Waiting for the bank's answer">
          The bank did not answer in time. Your money stays reserved, never lost: PayCore asks the bank what happened
          and either completes the transfer or returns your money.{' '}
          {checked
            ? checked.status === 'SUCCEEDED'
              ? 'Update: the bank confirmed it. The transfer is complete.'
              : checked.status === 'FAILED'
                ? `Update: ${checked.failureReason ?? 'it failed'}`
                : `Still ${checked.status.toLowerCase().replace('_', ' ')} after ${checked.statusChecks} check(s).`
            : null}
          <br />
          <button className="btn btn-ghost btn-sm" onClick={checkStatus}>
            Check status
          </button>
        </Notice>
      )}

      <dl className="review-list">
        <dt>Reference</dt>
        <dd>
          <code>{transaction.reference}</code>
        </dd>
        {transaction.providerReference && (
          <>
            <dt>Session ID</dt>
            <dd>
              <code className="wrap">{transaction.providerReference}</code>
            </dd>
          </>
        )}
        {failed && outcome.kind === 'external' && outcome.transfer.reversalReference && (
          <>
            <dt>Reversal</dt>
            <dd>
              <code>{outcome.transfer.reversalReference}</code>
            </dd>
          </>
        )}
      </dl>

      <div className="actions">
        <Link className="btn btn-ghost" to={`/app/transactions/${transaction.id}`}>
          View receipt
        </Link>
        <button className="btn btn-primary" onClick={onAnother}>
          New transfer
        </button>
      </div>

      <details className="dev-note">
        <summary>
          <Icon name="code" size={16} /> What if the app retried?
        </summary>
        <p className="muted small">
          Send the exact same request again, same idempotency key, as an app would after losing the response.
        </p>
        <button className="btn btn-ghost btn-sm" onClick={resend}>
          Resend the same request
        </button>
        {retryNote && <Notice tone="info">{retryNote}</Notice>}
      </details>
    </section>
  );
}
