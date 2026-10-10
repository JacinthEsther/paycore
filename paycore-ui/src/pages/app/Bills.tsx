import { useEffect, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { api, ApiError, errorMessage } from '../../api/client';
import type { Account, BillCategory, BillCustomer, Biller, BillPayment, BillProduct, Payment } from '../../api/types';
import { refreshBanking, useBanking } from '../../banking';
import { ScreenTitle } from '../../components/Layout';
import { PinInput } from '../../components/PinInput';
import { Avatar, CopyButton, Field, formatMoney, Icon, newIdempotencyKey, Notice } from '../../components/ui';
import { markDone } from '../../journey';
import { useTransactionSecurity } from '../../security';

const CATEGORIES: { id: BillCategory; label: string }[] = [
  { id: 'AIRTIME', label: 'Airtime' },
  { id: 'DATA', label: 'Data' },
  { id: 'ELECTRICITY', label: 'Electricity' },
  { id: 'TV', label: 'TV' },
];

const NUMBER_PATTERNS: Record<BillCategory, RegExp> = {
  AIRTIME: /^0\d{10}$/,
  DATA: /^0\d{10}$/,
  ELECTRICITY: /^\d{11,13}$/,
  TV: /^\d{10,12}$/,
};

interface Draft {
  biller: Biller;
  product: BillProduct | null;
  meterType: string | null;
  customerNumber: string;
  customer: BillCustomer | null;
  amount: number;
  key: string;
}

function requestBody(draft: Draft) {
  return {
    billerId: draft.biller.id,
    productCode: draft.product?.code ?? draft.meterType,
    customerNumber: draft.customerNumber,
    amount: draft.product ? null : draft.amount,
    idempotencyKey: draft.key,
  };
}

export function Bills() {
  const { account, balance, loading } = useBanking();
  const [draft, setDraft] = useState<Draft | null>(null);
  const [bill, setBill] = useState<BillPayment | null>(null);
  const [pin, setPin] = useState('');

  if (loading) return <p className="muted">Loading…</p>;

  if (!account || account.status !== 'ACTIVE' || !balance) {
    return (
      <>
        <ScreenTitle title="Pay bills" />
        <Notice tone="warn">
          Your account needs to be active before you can pay bills. <Link to="/app">See what is left to do</Link>.
        </Notice>
      </>
    );
  }

  if (bill && draft) {
    return (
      <Receipt
        draft={draft}
        bill={bill}
        account={account}
        pin={pin}
        onAnother={() => {
          setDraft(null);
          setBill(null);
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
        onPaid={(paid, usedPin) => {
          setPin(usedPin);
          setBill(paid);
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
  const [billers, setBillers] = useState<Biller[]>([]);
  const [billersError, setBillersError] = useState<string | null>(null);
  const [category, setCategory] = useState<BillCategory>('AIRTIME');
  const [billerId, setBillerId] = useState('');
  const [products, setProducts] = useState<BillProduct[]>([]);
  const [productCode, setProductCode] = useState('');
  const [meterType, setMeterType] = useState('');
  const [customerNumber, setCustomerNumber] = useState('');
  const [customer, setCustomer] = useState<BillCustomer | null>(null);
  const [verifyError, setVerifyError] = useState<string | null>(null);
  const [checking, setChecking] = useState(false);
  const [amount, setAmount] = useState('');

  useEffect(() => {
    api<Biller[]>('/api/v1/bills/billers')
      .then(setBillers)
      .catch((e) => setBillersError(errorMessage(e)));
  }, []);

  const inCategory = billers.filter((b) => b.category === category);
  const biller = inCategory.find((b) => b.id === billerId) ?? null;

  useEffect(() => {
    setBillerId(inCategory[0]?.id ?? '');
  }, [category, billers]);

  useEffect(() => {
    setProducts([]);
    setProductCode('');
    setMeterType(biller?.meterTypes?.[0] ?? '');
    setCustomer(null);
    setVerifyError(null);
    if (!biller?.products) return;

    let cancelled = false;
    api<BillProduct[]>(`/api/v1/bills/billers/${biller.id}/products`)
      .then((list) => {
        if (cancelled) return;
        setProducts(list);
        setProductCode(list[0]?.code ?? '');
      })
      .catch((e) => !cancelled && setVerifyError(errorMessage(e)));
    return () => {
      cancelled = true;
    };
  }, [biller]);

  const numberValid = NUMBER_PATTERNS[category].test(customerNumber);

  useEffect(() => {
    setCustomer(null);
    setVerifyError(null);
    if (!biller?.verified || !numberValid) return;
    if (biller.meterTypes?.length && !meterType) return;

    let cancelled = false;
    setChecking(true);
    api<BillCustomer>('/api/v1/bills/verify', {
      body: { billerId: biller.id, productCode: meterType || null, customerNumber },
    })
      .then((result) => !cancelled && setCustomer(result))
      .catch((e) => !cancelled && setVerifyError(errorMessage(e)))
      .finally(() => !cancelled && setChecking(false));
    return () => {
      cancelled = true;
    };
  }, [biller, meterType, customerNumber, numberValid]);

  const product = products.find((p) => p.code === productCode) ?? null;
  const amountValue = biller?.products ? (product?.amount ?? 0) : Number(amount);
  const tooMuch = amountValue > available;
  const outOfRange =
    !biller?.products &&
    !!amount &&
    ((biller?.minAmount != null && amountValue < biller.minAmount) ||
      (biller?.maxAmount != null && amountValue > biller.maxAmount));
  const ready =
    !!biller &&
    numberValid &&
    (!biller.verified || !!customer) &&
    (biller.products ? !!product : !!amount && !outOfRange) &&
    !tooMuch;

  function submit(e: FormEvent) {
    e.preventDefault();
    if (!ready || !biller) return;
    onContinue({
      biller,
      product: biller.products ? product : null,
      meterType: biller.meterTypes?.length ? meterType : null,
      customerNumber,
      customer,
      amount: amountValue,
      key: newIdempotencyKey('bill'),
    });
  }

  const numberLabel = biller?.numberLabel ?? 'Number';

  return (
    <>
      <ScreenTitle title="Pay bills">Airtime, data, electricity and TV, straight from your balance.</ScreenTitle>

      <form className="panel form" onSubmit={submit}>
        <div className="bank-picker" role="radiogroup" aria-label="Bill type">
          {CATEGORIES.map((c) => (
            <button
              type="button"
              key={c.id}
              role="radio"
              aria-checked={c.id === category}
              className={`bank-option ${c.id === category ? 'selected' : ''}`}
              onClick={() => setCategory(c.id)}
            >
              <Icon name="bolt" />
              {c.label}
            </button>
          ))}
        </div>

        {billersError && <Notice tone="bad">{billersError}</Notice>}

        <Field label={category === 'AIRTIME' || category === 'DATA' ? 'Network' : 'Provider'}>
          <select value={billerId} onChange={(e) => setBillerId(e.target.value)} required>
            {inCategory.map((b) => (
              <option key={b.id} value={b.id}>
                {b.name}
              </option>
            ))}
          </select>
        </Field>

        {biller?.meterTypes && biller.meterTypes.length > 0 && (
          <div className="bank-picker" role="radiogroup" aria-label="Meter type">
            {biller.meterTypes.map((type) => (
              <button
                type="button"
                key={type}
                role="radio"
                aria-checked={type === meterType}
                className={`bank-option ${type === meterType ? 'selected' : ''}`}
                onClick={() => setMeterType(type)}
              >
                {type === 'prepaid' ? 'Prepaid' : type === 'postpaid' ? 'Postpaid' : type}
              </button>
            ))}
          </div>
        )}

        <Field label={numberLabel}>
          <input
            value={customerNumber}
            inputMode="numeric"
            maxLength={13}
            placeholder={category === 'AIRTIME' || category === 'DATA' ? 'e.g. 08031234567' : numberLabel}
            required
            onChange={(e) => setCustomerNumber(e.target.value.replace(/\D/g, ''))}
          />
        </Field>

        {checking && <p className="muted small">Checking with {biller?.name}…</p>}
        {customer && (
          <div className="beneficiary">
            <Avatar name={customer.name} size="sm" />
            <div>
              <strong>{customer.name}</strong>
              {customer.address && <span className="muted small">{customer.address}</span>}
            </div>
            <Icon name="check" />
          </div>
        )}
        {verifyError && <Notice tone="bad">{verifyError}</Notice>}

        {biller?.products ? (
          <Field label={category === 'TV' ? 'Bouquet' : 'Bundle'}>
            <select value={productCode} onChange={(e) => setProductCode(e.target.value)} required>
              {products.map((p) => (
                <option key={p.code} value={p.code}>
                  {p.name} · {formatMoney(p.amount, account.currency)}
                </option>
              ))}
            </select>
          </Field>
        ) : (
          <Field
            label="Amount"
            hint={
              biller?.minAmount != null && biller.maxAmount != null
                ? `${formatMoney(biller.minAmount, account.currency)} to ${formatMoney(biller.maxAmount, account.currency)} · Available: ${formatMoney(available, account.currency)}`
                : `Available: ${formatMoney(available, account.currency)}`
            }
          >
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
        )}
        {outOfRange && <Notice tone="warn">That amount is outside what {biller?.name} accepts.</Notice>}
        {tooMuch && <Notice tone="warn">That is more than your available balance.</Notice>}

        <p className="muted small">
          The biller is simulated, and for airtime and electricity the kobo pick what happens: <code>.99</code> is
          declined and reversed at once; <code>.98</code> is delivered but the answer is lost, so it shows as processing
          until PayCore's status check confirms it; <code>.97</code> never reaches the biller, so the check reverses it.
          Meter and smartcard numbers ending in <code>0000</code> do not exist.
        </p>

        <button className="btn btn-primary btn-block" disabled={!ready}>
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
  onPaid,
}: {
  draft: Draft;
  account: Account;
  onBack: () => void;
  onPaid: (bill: BillPayment, pin: string) => void;
}) {
  const security = useTransactionSecurity();
  const [pin, setPin] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function pay() {
    setBusy(true);
    setError(null);
    try {
      const bill = await api<BillPayment>(`/api/v1/ledger/accounts/${account.id}/bill-payments`, {
        body: requestBody(draft),
        headers: { 'X-Transaction-PIN': pin },
      });
      markDone('bill');
      onPaid(bill, pin);
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
      <ScreenTitle title="Confirm payment" />
      <section className="panel review">
        <p className="review-amount">{formatMoney(draft.amount, account.currency)}</p>
        <dl className="review-list">
          <dt>Biller</dt>
          <dd>{draft.biller.name}</dd>
          {draft.product && (
            <>
              <dt>{draft.biller.category === 'TV' ? 'Bouquet' : 'Bundle'}</dt>
              <dd>{draft.product.name}</dd>
            </>
          )}
          {draft.meterType && (
            <>
              <dt>Meter type</dt>
              <dd>{draft.meterType === 'prepaid' ? 'Prepaid' : 'Postpaid'}</dd>
            </>
          )}
          <dt>{draft.biller.numberLabel}</dt>
          <dd>
            {draft.customer && <strong>{draft.customer.name}</strong>}
            <span className={draft.customer ? 'muted small' : undefined}>{draft.customerNumber}</span>
          </dd>
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
          <button className="btn btn-primary" onClick={pay} disabled={busy || !security.ready || pin.length !== 4}>
            {busy ? 'Paying…' : 'Pay'}
          </button>
        </div>
      </section>
    </>
  );
}

function Receipt({
  draft,
  bill,
  account,
  pin,
  onAnother,
}: {
  draft: Draft;
  bill: BillPayment;
  account: Account;
  pin: string;
  onAnother: () => void;
}) {
  const failed = bill.billStatus === 'FAILED';
  const pending = bill.billStatus === 'PENDING';
  const [retryNote, setRetryNote] = useState<string | null>(null);
  const [checked, setChecked] = useState<Payment | null>(null);

  async function checkStatus() {
    try {
      setChecked(await api<Payment>(`/api/v1/payments/${bill.paymentId}`));
      refreshBanking();
    } catch (e) {
      setRetryNote(errorMessage(e));
    }
  }

  async function resend() {
    try {
      const again = await api<BillPayment>(`/api/v1/ledger/accounts/${account.id}/bill-payments`, {
        body: requestBody(draft),
        headers: { 'X-Transaction-PIN': pin },
      });
      setRetryNote(
        again.id === bill.id
          ? `Same payment back (${again.reference}). The idempotency key was recognised, so the biller was not paid twice.`
          : `A different payment came back (${again.reference}). That should not happen.`,
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
      <h1>{failed ? 'Payment failed' : pending ? 'Payment processing' : 'Payment successful'}</h1>
      <p className="review-amount">{formatMoney(bill.amount, bill.currency)}</p>
      <p className="muted">
        {failed
          ? `${draft.biller.name} declined it, so PayCore reversed the debit. Your money is back in your account.`
          : `${bill.productName ?? draft.biller.name} for ${draft.customer?.name ?? bill.customerNumber}`}
      </p>
      {failed && bill.failureReason && <Notice tone="warn">{bill.failureReason}</Notice>}
      {pending && (
        <Notice tone="warn" title="Waiting for the biller's answer">
          The biller did not answer in time. Your money stays reserved, never lost: PayCore asks the biller what
          happened and either completes the payment or returns your money.{' '}
          {checked
            ? checked.status === 'SUCCEEDED'
              ? 'Update: the biller confirmed it. The payment is complete.'
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

      {bill.token && (
        <div className="beneficiary">
          <div>
            <span className="muted small">Meter token</span>
            <strong>
              <code className="wrap">{bill.token}</code>
            </strong>
          </div>
          <CopyButton text={bill.token} />
        </div>
      )}

      <dl className="review-list">
        <dt>Reference</dt>
        <dd>
          <code>{bill.reference}</code>
        </dd>
        <dt>{draft.biller.numberLabel}</dt>
        <dd>{bill.customerNumber}</dd>
        {failed && bill.reversalReference && (
          <>
            <dt>Reversal</dt>
            <dd>
              <code>{bill.reversalReference}</code>
            </dd>
          </>
        )}
      </dl>

      <div className="actions">
        <Link className="btn btn-ghost" to={`/app/transactions/${bill.id}`}>
          View receipt
        </Link>
        <button className="btn btn-primary" onClick={onAnother}>
          Pay another bill
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
