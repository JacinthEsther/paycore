import { useEffect, useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { decodeClaims, errorMessage } from '../api/client';
import { useDemoInfo } from '../api/demo';
import { useAuth } from '../auth/AuthContext';
import { Avatar, Field, Notice } from '../components/ui';
import { markDone, rememberCustomer, useJourney } from '../journey';
import { staffRoleOf } from '../staff';

export function Login() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const { session, login, logout } = useAuth();
  const { customerEmail } = useJourney();

  const mode = params.get('as');
  const demo = useDemoInfo();
  const [email, setEmail] = useState(
    params.get('staff') ?? (mode === 'customer' || params.get('registered') ? customerEmail ?? '' : ''),
  );
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [signingInAs, setSigningInAs] = useState<string | null>(null);

  const prefill = params.get('staff');
  const [autoSignedIn, setAutoSignedIn] = useState(false);
  useEffect(() => {
    if (!prefill || !demo || session || autoSignedIn) return;
    if (!demo.staff.some((member) => member.email === prefill)) return;
    setAutoSignedIn(true);
    void use(prefill);
  }, [prefill, demo, session, autoSignedIn]);

  async function use(staffEmail: string) {
    if (!demo) return;
    setEmail(staffEmail);
    setPassword(demo.staffPassword);
    setSigningInAs(staffEmail);
    await signIn(staffEmail, demo.staffPassword);
    setSigningInAs(null);
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    await signIn(email, password);
  }

  async function signIn(email: string, password: string) {
    setBusy(true);
    setError(null);
    try {
      const response = await login(email, password);
      const roles = decodeClaims(response.accessToken)?.roles ?? [];
      const next = params.get('next');

      if (staffRoleOf(roles)) {
        if (roles.includes('COMPLIANCE')) markDone('admin-login');
        navigate(next ?? '/admin');
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

  const staff = demo?.staff ?? [];

  const staffPicker = mode === 'staff' && demo && (
    <section className="auth-card demo-staff">
      <header className="panel-head">
        <h2>Pick a person to work as</h2>
        <span className="chip chip-warn">Shared demo accounts</span>
      </header>
      <p className="muted small">
        Each person has one job in the back office. Click <strong>Sign in</strong> to work as them; switch to another
        person any time to see who is allowed to do what. Everyone shares these accounts (password{' '}
        <code>{demo.staffPassword}</code>), and nobody can suspend them or change their roles.
      </p>
      {error && signingInAs === null && staff.some((member) => member.email === email) && (
        <Notice tone="bad">{error}</Notice>
      )}
      <ul className="staff-list">
        {staff.map((member) => (
          <li key={member.email}>
            <Avatar name={member.name} size="sm" />
            <div>
              <strong>
                {member.name} <span className="chip">{member.title}</span>
              </strong>
              <span className="muted small">{member.duty}</span>
              <code className="small">{member.email}</code>
            </div>
            <button type="button" className="btn btn-primary btn-sm" disabled={busy} onClick={() => void use(member.email)}>
              {signingInAs === member.email ? 'Signing in…' : 'Sign in'}
            </button>
          </li>
        ))}
      </ul>
    </section>
  );

  return (
    <div className="auth-page">
      {staffPicker}

      {mode !== 'staff' && demo && (
        <Link to="/login?as=staff" className="auth-card staff-entry">
          <span>
            <strong>Exploring the back office?</strong>
            <span className="muted small block">
              Work as Support, Compliance, Operations or the System Admin. No password needed.
            </span>
          </span>
          <span className="btn btn-ghost btn-sm">Staff sign in →</span>
        </Link>
      )}

      <section className="auth-card">
        <h1>{mode === 'staff' ? (demo ? 'Or sign in with email' : 'Staff sign in') : mode === 'customer' ? 'Welcome back' : 'Sign in'}</h1>
        <p className="muted">
          {mode === 'staff'
            ? 'The back office. Each person has one job, and the system holds them to it.'
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
          {error && !staff.some((member) => member.email === email) && <Notice tone="bad">{error}</Notice>}
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

      {mode === 'staff' && demo === null && (
        <Notice tone="warn">Demo mode is off on this server, so there are no shared staff accounts to show.</Notice>
      )}
    </div>
  );
}
