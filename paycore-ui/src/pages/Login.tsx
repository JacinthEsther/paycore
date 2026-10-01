import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { decodeClaims, errorMessage } from '../api/client';
import { useDemoInfo } from '../api/demo';
import { useAuth } from '../auth/AuthContext';
import { Avatar, Field, Notice } from '../components/ui';
import { markDone, rememberCustomer, useJourney } from '../journey';

const STAFF_ROLES = ['ADMIN', 'OPERATIONS', 'SUPPORT'];

export function Login() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const { session, login, logout } = useAuth();
  const { customerEmail } = useJourney();

  const mode = params.get('as'); // 'staff' | 'customer' | null
  const demo = useDemoInfo();
  const [email, setEmail] = useState(mode === 'customer' || params.get('registered') ? customerEmail ?? '' : '');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  function use(staffEmail: string) {
    if (!demo) return;
    setEmail(staffEmail);
    setPassword(demo.adminPassword);
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const response = await login(email, password);
      const roles = decodeClaims(response.accessToken)?.roles ?? [];
      const next = params.get('next');

      if (roles.some((role) => STAFF_ROLES.includes(role))) {
        if (roles.includes('ADMIN')) markDone('admin-login');
        navigate(next ?? (roles.includes('OPERATIONS') ? '/admin/operations' : '/admin'));
      } else {
        markDone('login');
        rememberCustomer(response.email);
        navigate(next ?? '/app');
      }
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  if (session) {
    return (
      <div className="auth-page">
        <section className="auth-card">
          <h1>You're signed in</h1>
          <p className="muted">
            Signed in as <strong>{session.email}</strong>. Sign out first to switch to another account.
          </p>
          <div className="actions">
            <button className="btn btn-primary" onClick={() => void logout()}>
              Sign out
            </button>
            <Link className="btn btn-ghost" to="/app">
              Continue
            </Link>
          </div>
        </section>
      </div>
    );
  }

  const staff = demo
    ? [
        { email: demo.adminEmail, name: 'PayCore Admin', duty: 'Compliance: reviews KYC, activates accounts. Cannot move money.' },
        ...demo.operationsStaff,
      ]
    : [];

  return (
    <div className="auth-page">
      <section className="auth-card">
        <h1>{mode === 'staff' ? 'Staff sign in' : mode === 'customer' ? 'Welcome back' : 'Sign in'}</h1>
        <p className="muted">
          {mode === 'staff'
            ? 'The back office: compliance and operations.'
            : 'Sign in to your PayCore account.'}
        </p>

        {params.get('registered') && <Notice tone="good">Profile created. Sign in with the password you just chose.</Notice>}
        {params.get('switched') && <Notice tone="info">You have been signed out on the server. Sign in with another account.</Notice>}

        <form onSubmit={submit} className="form">
          <Field label="Email">
            <input required type="email" value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="username" />
          </Field>
          <Field label="Password">
            <input required type="password" minLength={8} value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" />
          </Field>
          {error && <Notice tone="bad">{error}</Notice>}
          <button className="btn btn-primary btn-block btn-lg" disabled={busy}>
            {busy ? 'Signing in…' : 'Sign in'}
          </button>
        </form>

        {mode !== 'staff' && (
          <p className="muted small center">
            New to PayCore? <Link to="/register">Open an account</Link>
            {demo && (
              <>
                {' · '}
                <Link to="/login?as=staff">Staff sign in</Link>
              </>
            )}
          </p>
        )}
        {mode === 'staff' && (
          <p className="muted small center">
            <Link to="/login">Customer sign in</Link>
          </p>
        )}
      </section>

      {mode === 'staff' && demo && (
        <section className="auth-card demo-staff">
          <header className="panel-head">
            <h2>Shared demo staff</h2>
            <span className="chip chip-warn">Public</span>
          </header>
          <p className="muted small">
            Every visitor shares these accounts, all with the password <code>{demo.adminPassword}</code>. They are
            protected: nobody can suspend them or change their roles. Corrections need both officers: one requests,
            the other approves.
          </p>
          <ul className="staff-list">
            {staff.map((member) => (
              <li key={member.email}>
                <Avatar name={member.name} size="sm" />
                <div>
                  <strong>{member.name}</strong>
                  <span className="muted small">{member.duty}</span>
                  <code className="small">{member.email}</code>
                </div>
                <button type="button" className="btn btn-ghost btn-sm" onClick={() => use(member.email)}>
                  Use
                </button>
              </li>
            ))}
          </ul>
        </section>
      )}
      {mode === 'staff' && demo === null && (
        <Notice tone="warn">Demo mode is off on this server, so there are no shared staff accounts to show.</Notice>
      )}
    </div>
  );
}
