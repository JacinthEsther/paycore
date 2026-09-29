import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { api, errorMessage } from '../api/client';
import type { Customer } from '../api/types';
import { Card, Field, Notice, PageHeader } from '../components/ui';
import { markDone, rememberCustomer } from '../journey';

const COUNTRIES = [
  ['NG', 'Nigeria (+234)'],
  ['GH', 'Ghana (+233)'],
  ['KE', 'Kenya (+254)'],
  ['ZA', 'South Africa (+27)'],
  ['GB', 'United Kingdom (+44)'],
  ['US', 'United States (+1)'],
];

export function Register() {
  const navigate = useNavigate();
  const [form, setForm] = useState({
    firstName: '',
    lastName: '',
    email: '',
    countryCode: 'NG',
    phoneNumber: '',
    password: '',
  });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const set = (key: keyof typeof form) => (e: { target: { value: string } }) => setForm({ ...form, [key]: e.target.value });

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const customer = await api<Customer>('/api/v1/customers', { body: form, auth: 'none' });
      rememberCustomer(customer.email);
      markDone('register');
      // The email is prefilled from the walkthrough state, never put in the URL.
      navigate('/login?registered=1');
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="narrow">
      <PageHeader eyebrow="Step 1 · Customer" title="Create your PayCore account">
        This calls <code>POST /api/v1/customers</code>. It's the only customer endpoint open without a token. The
        server gives you the <code>CUSTOMER</code> role; the request has no way to choose a role.
      </PageHeader>

      <Card>
        <form onSubmit={submit} className="form">
          <div className="row-2">
            <Field label="First name">
              <input required maxLength={100} value={form.firstName} onChange={set('firstName')} autoComplete="given-name" />
            </Field>
            <Field label="Last name">
              <input required maxLength={100} value={form.lastName} onChange={set('lastName')} autoComplete="family-name" />
            </Field>
          </div>
          <Field label="Email" hint="Any address works; nothing is sent to it.">
            <input required type="email" value={form.email} onChange={set('email')} autoComplete="email" />
          </Field>
          <div className="row-phone">
            <Field label="Country">
              <select value={form.countryCode} onChange={set('countryCode')}>
                {COUNTRIES.map(([code, name]) => (
                  <option key={code} value={code}>
                    {name}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="Phone number" hint="Checked with libphonenumber, e.g. 0803 123 4567 for Nigeria.">
              <input required value={form.phoneNumber} onChange={set('phoneNumber')} autoComplete="tel-national" inputMode="tel" />
            </Field>
          </div>
          <Field label="Password" hint="8 to 128 characters. Stored as a BCrypt hash.">
            <input required type="password" minLength={8} maxLength={128} value={form.password} onChange={set('password')} autoComplete="new-password" />
          </Field>

          {error && <Notice tone="bad">{error}</Notice>}

          <button className="btn btn-primary btn-block" disabled={busy}>
            {busy ? 'Creating account…' : 'Create account'}
          </button>
          <p className="muted small center">
            Already registered? <Link to="/login">Sign in</Link>
          </p>
        </form>
      </Card>
    </div>
  );
}
