import { useEffect, useState, type FormEvent } from 'react';
import { api, errorMessage } from '../../api/client';
import { useDemoInfo } from '../../api/demo';
import type { CodeSent, Device } from '../../api/types';
import { ScreenTitle } from '../../components/Layout';
import { PinInput } from '../../components/PinInput';
import { Field, formatDate, Icon, Notice, StatusBadge } from '../../components/ui';
import { refreshSecurity, useTransactionSecurity } from '../../security';

export function PinAndDevices() {
  const security = useTransactionSecurity();

  if (security.loading) return <p className="muted">Loading…</p>;

  const trusted = security.device?.status === 'TRUSTED';

  return (
    <>
      <ScreenTitle title="PIN & devices" back="/app/profile" />

      <ThisDevice device={security.device} />
      {trusted && <TransactionPin set={!!security.pin?.set} locked={!!security.pin?.locked} />}
      <DeviceList canRemove={trusted} />
    </>
  );
}

function ThisDevice({ device }: { device: Device | null }) {
  const [sent, setSent] = useState<CodeSent | null>(null);
  const [code, setCode] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  if (!device) {
    return (
      <Notice tone="warn" title="This browser is not registered.">
        Sign out and sign in again to register it.
      </Notice>
    );
  }

  async function sendCode() {
    setError(null);
    try {
      setSent(await api<CodeSent>('/api/v1/devices/current/confirmation-code', { method: 'POST' }));
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  async function confirm(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api('/api/v1/devices/current/confirm', { body: { code } });
      refreshSecurity();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="panel">
      <header className="panel-head">
        <h2>This device</h2>
        <StatusBadge status={device.status} label={device.status === 'TRUSTED' ? 'Trusted' : 'Not confirmed'} />
      </header>
      <p className="muted small">{device.label}</p>

      {device.coolingOffUntil && (
        <Notice tone="info">New device: transfers are capped at ₦20,000 until {formatDate(device.coolingOffUntil)}.</Notice>
      )}

      {device.status === 'PENDING' && (
        <>
          <p>Confirm this device to send money from it.</p>
          {!sent ? (
            <button className="btn btn-primary" onClick={sendCode}>
              Email me a code
            </button>
          ) : (
            <form className="form" onSubmit={confirm}>
              <Field label={`Code sent to ${sent.sentTo}`} hint={<DemoCode sentAt={sent} />}>
                <input
                  value={code}
                  inputMode="numeric"
                  maxLength={6}
                  placeholder="6-digit code"
                  autoFocus
                  onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
                />
              </Field>
              <div className="actions">
                <button type="button" className="btn btn-ghost" onClick={sendCode}>
                  Resend
                </button>
                <button className="btn btn-primary" disabled={busy || code.length !== 6}>
                  {busy ? 'Confirming…' : 'Confirm device'}
                </button>
              </div>
            </form>
          )}
        </>
      )}

      {error && <Notice tone="bad">{error}</Notice>}
    </section>
  );
}

type PinMode = 'view' | 'change' | 'reset';

function TransactionPin({ set, locked }: { set: boolean; locked: boolean }) {
  const [mode, setMode] = useState<PinMode>(locked ? 'reset' : 'view');
  const [current, setCurrent] = useState('');
  const [pin, setPin] = useState('');
  const [repeat, setRepeat] = useState('');
  const [code, setCode] = useState('');
  const [sent, setSent] = useState<CodeSent | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (locked) setMode('reset');
  }, [locked]);

  const mismatch = repeat.length === 4 && pin !== repeat;
  const ready = pin.length === 4 && pin === repeat;

  function reset() {
    setCurrent('');
    setPin('');
    setRepeat('');
    setCode('');
    setSent(null);
    setError(null);
  }

  async function run(action: () => Promise<unknown>, message: string) {
    setBusy(true);
    setError(null);
    try {
      await action();
      reset();
      setMode('view');
      setDone(message);
      refreshSecurity();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  async function sendResetCode() {
    setError(null);
    try {
      setSent(await api<CodeSent>('/api/v1/security/pin/reset-code', { method: 'POST' }));
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  function submit(e: FormEvent) {
    e.preventDefault();
    if (!set) {
      void run(() => api('/api/v1/security/pin', { body: { pin } }), 'PIN set.');
    } else if (mode === 'change') {
      void run(() => api('/api/v1/security/pin', { method: 'PUT', body: { currentPin: current, newPin: pin } }), 'PIN changed.');
    } else if (mode === 'reset') {
      void run(() => api('/api/v1/security/pin/reset', { body: { code, newPin: pin } }), 'PIN reset.');
    }
  }

  const newPinFields = (
    <>
      <PinInput label="New PIN" value={pin} onChange={setPin} autoFocus={!set} />
      <PinInput label="Repeat new PIN" value={repeat} onChange={setRepeat} />
      {mismatch && <Notice tone="warn">The PINs don't match.</Notice>}
    </>
  );

  return (
    <section className="panel">
      <header className="panel-head">
        <h2>Transaction PIN</h2>
        {set && <StatusBadge status={locked ? 'FROZEN' : 'ACTIVE'} label={locked ? 'Locked' : 'Set'} />}
      </header>

      {done && <Notice tone="good">{done}</Notice>}

      {!set && (
        <form className="form" onSubmit={submit}>
          <p className="muted small">You'll enter it for every transfer. Avoid 1234 or 0000.</p>
          {newPinFields}
          <button className="btn btn-primary" disabled={busy || !ready}>
            Set PIN
          </button>
        </form>
      )}

      {set && mode === 'view' && (
        <div className="actions">
          <button className="btn btn-ghost" onClick={() => setMode('change')}>
            Change PIN
          </button>
          <button className="btn btn-ghost" onClick={() => setMode('reset')}>
            Forgot PIN
          </button>
        </div>
      )}

      {set && mode === 'change' && (
        <form className="form" onSubmit={submit}>
          <PinInput label="Current PIN" value={current} onChange={setCurrent} autoFocus />
          {newPinFields}
          <div className="actions">
            <button type="button" className="btn btn-ghost" onClick={() => (reset(), setMode('view'))}>
              Cancel
            </button>
            <button className="btn btn-primary" disabled={busy || current.length !== 4 || !ready}>
              Change PIN
            </button>
          </div>
        </form>
      )}

      {set && mode === 'reset' && (
        <form className="form" onSubmit={submit}>
          {locked && <Notice tone="warn">Too many wrong PINs. Reset it to keep sending money.</Notice>}
          {!sent ? (
            <button type="button" className="btn btn-primary" onClick={sendResetCode}>
              Email me a code
            </button>
          ) : (
            <>
              <Field label={`Code sent to ${sent.sentTo}`} hint={<DemoCode sentAt={sent} />}>
                <input
                  value={code}
                  inputMode="numeric"
                  maxLength={6}
                  placeholder="6-digit code"
                  onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
                />
              </Field>
              {newPinFields}
              <div className="actions">
                {!locked && (
                  <button type="button" className="btn btn-ghost" onClick={() => (reset(), setMode('view'))}>
                    Cancel
                  </button>
                )}
                <button className="btn btn-primary" disabled={busy || code.length !== 6 || !ready}>
                  Reset PIN
                </button>
              </div>
            </>
          )}
        </form>
      )}

      {error && <Notice tone="bad">{error}</Notice>}
    </section>
  );
}

function DeviceList({ canRemove }: { canRemove: boolean }) {
  const [devices, setDevices] = useState<Device[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function load() {
    try {
      setDevices(await api<Device[]>('/api/v1/devices'));
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  useEffect(() => {
    void load();
  }, []);

  async function remove(device: Device) {
    setError(null);
    try {
      await api(`/api/v1/devices/${device.id}`, { method: 'DELETE' });
      await load();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <section className="panel">
      <header className="panel-head">
        <h2>Your devices</h2>
      </header>
      {error && <Notice tone="bad">{error}</Notice>}
      <ul className="settings-list">
        {devices?.map((device) => (
          <li key={device.id}>
            <Icon name="card" />
            <div>
              <strong>
                {device.label ?? 'Unknown device'}
                {device.current && <span className="muted"> · this one</span>}
              </strong>
              <span className="muted small">Last used {formatDate(device.lastSeenAt)}</span>
            </div>
            {device.status === 'REVOKED' ? (
              <StatusBadge status="REVOKED" label="Removed" />
            ) : canRemove && !device.current ? (
              <button className="btn btn-ghost btn-sm" onClick={() => remove(device)}>
                Remove
              </button>
            ) : (
              <StatusBadge status={device.status} label={device.status === 'TRUSTED' ? 'Trusted' : 'Not confirmed'} />
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}

function DemoCode({ sentAt }: { sentAt: CodeSent }) {
  const demo = useDemoInfo();
  const [code, setCode] = useState<string | null>(null);

  useEffect(() => {
    if (!demo) return;
    api<{ subject: string }[]>('/api/v1/demo/outbox')
      .then((emails) => setCode(emails.map((e) => /code: (\d{6})/.exec(e.subject)?.[1]).find(Boolean) ?? null))
      .catch(() => setCode(null));
  }, [demo, sentAt]);

  return code ? <>Demo inbox: <code>{code}</code></> : null;
}
