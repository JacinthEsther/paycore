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
  POSTED: 'good',
  REVERSED: 'warn',
  INITIATED: 'info',
  SUSPENDED: 'bad',
  FROZEN: 'bad',
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

/** 0123456789 -> 012 345 6789, the way Nigerian account numbers are read out. */
export function formatAccountNumber(value: string) {
  return value.replace(/^(\d{3})(\d{3})(\d{4})$/, '$1 $2 $3');
}

/** 20000.5, 'NGN' -> ₦20,000.50 */
export function formatMoney(amount: number, currency: string) {
  return new Intl.NumberFormat('en-NG', { style: 'currency', currency }).format(amount);
}

/** Entries on PayCore's settlement account have no account number. */
export function accountLabel(accountNumber: string | null) {
  return accountNumber ? formatAccountNumber(accountNumber) : 'PayCore settlement';
}

/**
 * A fresh idempotency key for one money-moving request. Resending the same
 * request with the same key returns the original result instead of moving
 * the money again.
 */
export function newIdempotencyKey(prefix: string) {
  return `${prefix}-${crypto.randomUUID()}`;
}

export function formatDate(value: string | null | undefined) {
  if (!value) return '—';
  return new Date(value).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
}

/**
 * staff: the back office (ADMIN, OPERATIONS, SUPPORT). Otherwise the
 * banking app, which staff-only accounts (operations officers) cannot use.
 */
export function RequireAuth({ staff = false, children }: { staff?: boolean; children: ReactNode }) {
  const { session, isStaff, isCustomer } = useAuth();
  const location = useLocation();
  if (!session) {
    const target = getSignOutRedirect() ?? `/login?next=${encodeURIComponent(location.pathname)}${staff ? '&as=staff' : ''}`;
    return <Navigate to={target} replace />;
  }
  if (staff && !isStaff) return <Navigate to="/app" replace />;
  if (!staff && !isCustomer) return <Navigate to="/admin" replace />;
  return <>{children}</>;
}

/** Initials for an avatar: "Esther Agboniro" -> "EA". */
export function initials(name: string | null | undefined) {
  if (!name) return '?';
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]!.toUpperCase())
    .join('');
}

export function Avatar({ name, size = 'md' }: { name: string | null | undefined; size?: 'sm' | 'md' | 'lg' }) {
  return (
    <span className={`avatar avatar-${size}`} aria-hidden>
      {initials(name)}
    </span>
  );
}

const ICONS: Record<string, ReactNode> = {
  home: <path d="M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6h-6v6H4a1 1 0 0 1-1-1z" />,
  send: <path d="M4 12h13M12 5l7 7-7 7" />,
  receive: <path d="M20 12H7M12 19l-7-7 7-7" />,
  plus: <path d="M12 5v14M5 12h14" />,
  list: <path d="M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01" />,
  user: (
    <>
      <circle cx="12" cy="8" r="4" />
      <path d="M4 21c0-4 4-6 8-6s8 2 8 6" />
    </>
  ),
  shield: <path d="M12 3 4 6v6c0 5 3.5 8 8 9 4.5-1 8-4 8-9V6z" />,
  bank: <path d="M3 10 12 4l9 6M5 10v8M9 10v8M15 10v8M19 10v8M3 20h18" />,
  card: (
    <>
      <rect x="3" y="5" width="18" height="14" rx="2" />
      <path d="M3 10h18" />
    </>
  ),
  copy: (
    <>
      <rect x="8" y="8" width="12" height="12" rx="2" />
      <path d="M16 8V5a1 1 0 0 0-1-1H5a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h3" />
    </>
  ),
  eye: (
    <>
      <path d="M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z" />
      <circle cx="12" cy="12" r="3" />
    </>
  ),
  eyeOff: <path d="M3 3l18 18M10.6 5.1A10 10 0 0 1 12 5c6 0 10 7 10 7a17 17 0 0 1-3.2 3.9M6.1 6.1C3.6 7.8 2 12 2 12s4 7 10 7a9.7 9.7 0 0 0 4.2-.9" />,
  undo: <path d="M9 14 4 9l5-5M4 9h11a5 5 0 0 1 0 10h-3" />,
  check: <path d="M5 12.5 10 17 19 7" />,
  x: <path d="M6 6l12 12M18 6 6 18" />,
  logout: <path d="M15 4h4a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1h-4M10 17l5-5-5-5M15 12H3" />,
  queue: <path d="M4 6h16M4 12h10M4 18h6M17 15l3 3-3 3" />,
  search: (
    <>
      <circle cx="11" cy="11" r="7" />
      <path d="m20 20-3.5-3.5" />
    </>
  ),
  users: (
    <>
      <circle cx="9" cy="8" r="3.5" />
      <path d="M2 20c0-3.5 3-5.5 7-5.5s7 2 7 5.5M16 4.5a3.5 3.5 0 0 1 0 7M22 20c0-3-2-5-5-5.4" />
    </>
  ),
  idcard: (
    <>
      <rect x="3" y="5" width="18" height="14" rx="2" />
      <circle cx="9" cy="11" r="2" />
      <path d="M6 16c.5-1.5 1.7-2 3-2s2.5.5 3 2M14 10h4M14 13h3" />
    </>
  ),
  guide: <path d="M4 5a2 2 0 0 1 2-2h12v18H6a2 2 0 0 1-2-2zM8 7h6M8 11h6" />,
  code: <path d="m8 7-5 5 5 5M16 7l5 5-5 5" />,
};

export function Icon({ name, size = 20 }: { name: keyof typeof ICONS | string; size?: number }) {
  return (
    <svg
      className="icon"
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.8}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
    >
      {ICONS[name]}
    </svg>
  );
}

/** Copies text and briefly says so. */
export function CopyButton({ text, label = 'Copy' }: { text: string; label?: string }) {
  async function copy(e: React.MouseEvent<HTMLButtonElement>) {
    const button = e.currentTarget;
    try {
      await navigator.clipboard.writeText(text);
      button.dataset.copied = 'true';
      setTimeout(() => delete button.dataset.copied, 1500);
    } catch {
      // Clipboard blocked (e.g. not https); the number is on screen anyway.
    }
  }
  return (
    <button type="button" className="copy-btn" onClick={copy} aria-label={`${label} ${text}`}>
      <Icon name="copy" size={16} />
      <span className="copy-label">{label}</span>
      <span className="copied-label">Copied</span>
    </button>
  );
}
