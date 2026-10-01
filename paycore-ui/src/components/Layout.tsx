import { useState, type ReactNode } from 'react';
import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { ApiInspector } from './ApiInspector';
import { JourneyBar } from './JourneyBar';
import { Avatar, Icon } from './ui';

function PreviewBanner() {
  return (
    <div className="preview-banner">
      <strong>Developer Preview</strong> · Sandbox data only. PayCore is a portfolio project, not a bank: no real money
      moves.
    </div>
  );
}

function Brand({ to = '/', subtitle }: { to?: string; subtitle?: string }) {
  return (
    <Link to={to} className="brand" aria-label="PayCore home">
      <span className="brand-mark" aria-hidden>
        P
      </span>
      <span className="brand-text">
        PayCore
        {subtitle && <span className="brand-sub">{subtitle}</span>}
      </span>
    </Link>
  );
}

/** The walkthrough, behind a button instead of on every screen. */
function GuideButton() {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button className="icon-btn" onClick={() => setOpen(true)} aria-label="Demo guide" title="Demo guide">
        <Icon name="guide" />
      </button>
      {open && (
        <div className="drawer-backdrop" onClick={() => setOpen(false)}>
          <aside className="drawer" onClick={(e) => e.stopPropagation()} aria-label="Demo guide">
            <header className="drawer-head">
              <h2>Demo guide</h2>
              <button className="icon-btn" onClick={() => setOpen(false)} aria-label="Close">
                <Icon name="x" />
              </button>
            </header>
            <p className="muted small">
              Try PayCore end to end: as a customer, as the compliance admin, and as two operations officers.
            </p>
            <div onClick={() => setOpen(false)}>
              <JourneyBar />
            </div>
          </aside>
        </div>
      )}
    </>
  );
}

function useSignOut() {
  const { logout } = useAuth();
  const navigate = useNavigate();
  return async () => {
    await logout('/login');
    navigate('/login');
  };
}

// ---------------------------------------------------------------------------
// Public pages: landing, sign in, register.
// ---------------------------------------------------------------------------

export function PublicLayout() {
  const { session, isStaff, isCustomer } = useAuth();

  return (
    <div className="shell shell-public">
      <PreviewBanner />
      <header className="public-bar">
        <Brand />
        <nav className="public-actions">
          <GuideButton />
          {session ? (
            <Link className="btn btn-primary btn-sm" to={isCustomer ? '/app' : isStaff ? '/admin' : '/app'}>
              Open PayCore
            </Link>
          ) : (
            <>
              <Link className="btn btn-ghost btn-sm" to="/login">
                Sign in
              </Link>
              <Link className="btn btn-primary btn-sm" to="/register">
                Open an account
              </Link>
            </>
          )}
        </nav>
      </header>
      <main className="public-main">
        <Outlet />
      </main>
      <ApiInspector />
    </div>
  );
}

// ---------------------------------------------------------------------------
// The banking app: sidebar on desktop, tab bar on phones.
// ---------------------------------------------------------------------------

const CUSTOMER_NAV: { to: string; label: string; icon: string; end?: boolean }[] = [
  { to: '/app', label: 'Home', icon: 'home', end: true },
  { to: '/app/transfer', label: 'Transfer', icon: 'send' },
  { to: '/app/add-money', label: 'Add money', icon: 'plus' },
  { to: '/app/transactions', label: 'Transactions', icon: 'list' },
  { to: '/app/profile', label: 'Profile', icon: 'user' },
];

export function CustomerLayout() {
  const { profile, session, isStaff } = useAuth();
  const signOut = useSignOut();
  const name = profile ? `${profile.firstName} ${profile.lastName}` : session?.email;

  return (
    <div className="shell shell-app">
      <PreviewBanner />
      <div className="app-frame">
        <aside className="sidebar">
          <Brand to="/app" />
          <nav className="side-nav" aria-label="Main">
            {CUSTOMER_NAV.map((item) => (
              <NavLink key={item.to} to={item.to} end={item.end}>
                <Icon name={item.icon} />
                {item.label}
              </NavLink>
            ))}
          </nav>
          {isStaff && (
            <Link to="/admin" className="side-switch">
              <Icon name="bank" /> Back office
            </Link>
          )}
          <button className="side-signout" onClick={signOut}>
            <Icon name="logout" /> Sign out
          </button>
        </aside>

        <div className="app-main">
          <header className="app-bar">
            <div className="app-bar-who">
              <Avatar name={name} />
              <div>
                <span className="muted small">Hello,</span>
                <strong>{profile?.firstName ?? session?.email}</strong>
              </div>
            </div>
            <div className="app-bar-actions">
              <GuideButton />
              <button className="icon-btn only-mobile" onClick={signOut} aria-label="Sign out">
                <Icon name="logout" />
              </button>
            </div>
          </header>
          <main className="app-content">
            <Outlet />
          </main>
        </div>
      </div>

      <nav className="tab-bar" aria-label="Main">
        {CUSTOMER_NAV.map((item) => (
          <NavLink key={item.to} to={item.to} end={item.end}>
            <Icon name={item.icon} />
            <span>{item.label}</span>
          </NavLink>
        ))}
      </nav>

      <ApiInspector />
    </div>
  );
}

// ---------------------------------------------------------------------------
// The back office: compliance (admin) and operations (maker-checker).
// ---------------------------------------------------------------------------

export function StaffLayout() {
  const { profile, session, claims, can, isCustomer } = useAuth();
  const signOut = useSignOut();
  const name = profile ? `${profile.firstName} ${profile.lastName}` : session?.email;
  const role = claims?.roles.includes('OPERATIONS') ? 'Operations' : claims?.roles.includes('ADMIN') ? 'Admin' : 'Support';

  const nav: { to: string; label: string; icon: string; show: boolean; end?: boolean }[] = [
    { to: '/admin', label: 'KYC reviews', icon: 'idcard', show: can('KYC_REVIEW'), end: true },
    { to: '/admin/customers', label: 'Customers', icon: 'users', show: can('CUSTOMER_READ') },
    { to: '/admin/operations', label: 'Approvals', icon: 'queue', show: can('LEDGER_REQUEST') || can('LEDGER_APPROVE') },
    { to: '/admin/ledger', label: 'Transactions', icon: 'search', show: can('ACCOUNT_VIEW_ALL') },
  ];

  return (
    <div className="shell shell-staff">
      <PreviewBanner />
      <div className="app-frame">
        <aside className="sidebar sidebar-staff">
          <Brand to="/admin" subtitle="Back office" />
          <nav className="side-nav" aria-label="Back office">
            {nav
              .filter((item) => item.show)
              .map((item) => (
                <NavLink key={item.to} to={item.to} end={item.end}>
                  <Icon name={item.icon} />
                  {item.label}
                </NavLink>
              ))}
          </nav>
          {isCustomer && (
            <Link to="/app" className="side-switch">
              <Icon name="home" /> My banking
            </Link>
          )}
          <button className="side-signout" onClick={signOut}>
            <Icon name="logout" /> Sign out
          </button>
        </aside>

        <div className="app-main">
          <header className="app-bar">
            <div className="app-bar-who">
              <Avatar name={name} />
              <div>
                <strong>{name}</strong>
                <span className="role-chip">{role}</span>
              </div>
            </div>
            <div className="app-bar-actions">
              <GuideButton />
              <button className="icon-btn only-mobile" onClick={signOut} aria-label="Sign out">
                <Icon name="logout" />
              </button>
            </div>
          </header>
          <nav className="staff-tabs only-mobile" aria-label="Back office">
            {nav
              .filter((item) => item.show)
              .map((item) => (
                <NavLink key={item.to} to={item.to} end={item.end}>
                  {item.label}
                </NavLink>
              ))}
          </nav>
          <main className="app-content app-content-wide">
            <Outlet />
          </main>
        </div>
      </div>
      <ApiInspector />
    </div>
  );
}

/** A screen title inside the app shells. */
export function ScreenTitle({ title, children, back }: { title: string; children?: ReactNode; back?: string }) {
  return (
    <header className="screen-title">
      {back && (
        <Link to={back} className="back-link">
          ← Back
        </Link>
      )}
      <h1>{title}</h1>
      {children && <p className="muted">{children}</p>}
    </header>
  );
}
