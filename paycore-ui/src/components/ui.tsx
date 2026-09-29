import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { getSignOutRedirect, useAuth } from '../auth/AuthContext';

type Tone = 'neutral' | 'good' | 'warn' | 'bad' | 'info';

const STATUS_TONES: Record<string, Tone> = {
  ACTIVE: 'good',
  VERIFIED: 'good',
  PASSED: 'good',
  PENDING_VERIFICATION: 'warn',
  NOT_STARTED: 'neutral',
  IN_PROGRESS: 'info',
  SUBMITTED: 'info',
  UNDER_REVIEW: 'info',
  ADDITIONAL_INFO_REQUIRED: 'warn',
  PENDING: 'warn',
  REQUIRES_REVIEW: 'warn',
  SUSPENDED: 'bad',
  CLOSED: 'bad',
  REJECTED: 'bad',
  FAILED: 'bad',
};

export function humanize(value: string) {
  return value.charAt(0) + value.slice(1).toLowerCase().replace(/_/g, ' ');
}

export function StatusBadge({ status, label }: { status: string; label?: string }) {
  return (
    <span className={`badge badge-${STATUS_TONES[status] ?? 'neutral'}`}>
      <span className="dot" aria-hidden />
      {label ?? humanize(status)}
    </span>
  );
}

export function Notice({ tone = 'info', title, children }: { tone?: Tone; title?: string; children: ReactNode }) {
  return (
    <div className={`notice notice-${tone}`} role={tone === 'bad' ? 'alert' : undefined}>
      {title && <strong>{title}</strong>}
      <div>{children}</div>
    </div>
  );
}

export function Field({ label, hint, children }: { label: string; hint?: ReactNode; children: ReactNode }) {
  return (
    <label className="field">
      <span className="field-label">{label}</span>
      {children}
      {hint && <span className="field-hint">{hint}</span>}
    </label>
  );
}

export function Card({ title, aside, children, className = '' }: { title?: ReactNode; aside?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={`card ${className}`}>
      {(title || aside) && (
        <header className="card-head">
          {title && <h2>{title}</h2>}
          {aside}
        </header>
      )}
      {children}
    </section>
  );
}

export function PageHeader({ eyebrow, title, children }: { eyebrow?: string; title: string; children?: ReactNode }) {
  return (
    <header className="page-head">
      {eyebrow && <p className="eyebrow">{eyebrow}</p>}
      <h1>{title}</h1>
      {children && <p className="lede">{children}</p>}
    </header>
  );
}

/** Shows an HTTP status the way the inspector does. */
export function HttpStatus({ status }: { status: number | null }) {
  if (status === null) return <span className="http http-none">—</span>;
  const tone = status < 300 ? 'ok' : status < 500 ? 'client' : 'server';
  return <span className={`http http-${tone}`}>{status}</span>;
}

export function formatDate(value: string | null | undefined) {
  if (!value) return '—';
  return new Date(value).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
}

export function RequireAuth({ admin = false, children }: { admin?: boolean; children: ReactNode }) {
  const { session, isAdmin } = useAuth();
  const location = useLocation();
  if (!session) {
    const target = getSignOutRedirect() ?? `/login?next=${encodeURIComponent(location.pathname)}${admin ? '&as=admin' : ''}`;
    return <Navigate to={target} replace />;
  }
  if (admin && !isAdmin) return <Navigate to="/app" replace />;
  return <>{children}</>;
}
