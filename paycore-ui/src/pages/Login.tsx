import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { decodeClaims, errorMessage } from '../api/client';
import { useDemoInfo } from '../api/demo';
import { useAuth } from '../auth/AuthContext';
import { Card, Field, Notice, PageHeader } from '../components/ui';
import { markDone, rememberCustomer, useJourney } from '../journey';

export function Login() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const { session, login, logout } = useAuth();
  const { customerEmail } = useJourney();

  const mode = params.get('as'); // 'admin' | 'customer' | null
  const demo = useDemoInfo();
  const [email, setEmail] = useState(mode === 'customer' || params.get('registered') ? customerEmail ?? '' : '');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  function fillAdmin() {
    if (!demo) return;
    setEmail(demo.adminEmail);
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

      if (roles.includes('ADMIN')) {
        markDone('admin-login');
        navigate(next ?? '/admin');
      } else {
        markDone('login');
        rememberCustomer(response.email);
        navigate(next ?? (mode === 'customer' ? '/app/kyc' : '/app'));
      }
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  if (session) {
    return (
      <div className="narrow">
        <PageHeader title="You're already signed in" />
        <Card>
          <p>
            Signed in as <strong>{session.email}</strong>. Sign out first to switch accounts.
          </p>
          <div className="actions">
            <button className="btn btn-primary" onClick={() => void logout()}>
              Sign out
            </button>
            <Link className="btn btn-ghost" to="/app">
              Back to dashboard
            </Link>
          </div>
        </Card>
      </div>
    );
  }

  return (
    <div className="narrow">
      {mode === 'admin' ? (
        <PageHeader eyebrow="Phase 2 · Admin" title="Sign in as the admin">
          Same login endpoint, different account. The access token for this account carries the <code>ADMIN</code> role
          and its permissions (<code>KYC_REVIEW</code>, <code>ROLE_MANAGE</code>, <code>CUSTOMER_SUSPEND</code>…),
          so Spring Security lets it through where it just blocked you.
        </PageHeader>
      ) : mode === 'customer' ? (
        <PageHeader eyebrow="Phase 3 · Customer" title="Sign back in as your customer">
          Sign in with the customer account you created and look at the reviewer's decision on your KYC.
        </PageHeader>
      ) : (
        <PageHeader eyebrow="Step 2 · Customer" title="Welcome back">
          <code>POST /api/v1/auth/login</code> checks your password against the BCrypt hash, opens a login session and
          returns a 15-minute access token (JWT) and a rotating refresh token.
        </PageHeader>
      )}

      {params.get('registered') && <Notice tone="good">Account created. Sign in with the password you just chose.</Notice>}
      {params.get('switched') && (
        <Notice tone="info" title="You've been signed out.">
          Your customer session was revoked on the server. Sign in with the admin account below.
        </Notice>
      )}

      {mode === 'admin' && demo && (
        <Card className="demo-creds" title="Demo admin account" aside={<span className="badge badge-warn">Shared</span>}>
          <dl className="kv">
            <dt>Email</dt>
            <dd>
              <code>{demo.adminEmail}</code>
            </dd>
            <dt>Password</dt>
            <dd>
              <code>{demo.adminPassword}</code>
            </dd>
          </dl>
          <p className="muted small">
            Every visitor shares this account, so it has a few guards: nobody can suspend it, edit it or change its
            roles. Everything else admins can do works normally.
          </p>
          <button className="btn btn-secondary" type="button" onClick={fillAdmin}>
            Fill in admin credentials
          </button>
        </Card>
      )}
      {mode === 'admin' && demo === null && (
        <Notice tone="warn">Demo mode is off on this server, so there are no shared admin credentials to show.</Notice>
      )}

      <Card>
        <form onSubmit={submit} className="form">
          <Field label="Email">
            <input required type="email" value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="username" />
          </Field>
          <Field label="Password">
            <input required type="password" minLength={8} value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" />
          </Field>
          {error && <Notice tone="bad">{error}</Notice>}
          <button className="btn btn-primary btn-block" disabled={busy}>
            {busy ? 'Signing in…' : 'Sign in'}
          </button>
          {mode !== 'admin' && (
            <p className="muted small center">
              New here? <Link to="/register">Create an account</Link>
              {demo && (
                <>
                  {' · '}
                  <Link to="/login?as=admin">Sign in as the demo admin</Link>
                </>
              )}
            </p>
          )}
        </form>
      </Card>
    </div>
  );
}
