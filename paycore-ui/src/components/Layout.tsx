import { useState, type ReactNode } from 'react';
import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { DESKS, staffRoleOf } from '../staff';
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
              Try PayCore end to end: as a customer, then as each person in the back office: Support, Compliance, an Operations Officer and Supervisor, and the System Admin.
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
              <Link className="btn btn-primary btn-sm hide-narrow" to="/register">
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

const CUSTOMER_NAV: { to: string; label: string; icon: string; end?: boolean; tabBar?: boolean }[] = [
  { to: '/app', label: 'Home', icon: 'home', end: true },
  { to: '/app/transfer', label: 'Transfer', icon: 'send' },
  { to: '/app/add-money', label: 'Add money', icon: 'plus' },
  { to: '/app/bills', label: 'Pay bills', icon: 'bolt', tabBar: false },
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
        {CUSTOMER_NAV.filter((item) => item.tabBar !== false).map((item) => (
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

interface StaffNavItem {
  to: string;
  label: string;
  icon: string;
  need: string[];
  why: string;
  end?: boolean;
}

const STAFF_NAV: StaffNavItem[] = [
  { to: '/admin', label: 'My desk', icon: 'home', need: [], why: '', end: true },
  {
    to: '/admin/kyc',
    label: 'KYC reviews',
    icon: 'idcard',
    need: ['KYC_REVIEW'],
    why: 'Only Compliance decides who may bank with PayCore and at what limits.',
  },
  {
    to: '/admin/customers',
    label: 'Customers',
    icon: 'users',
    need: ['CUSTOMER_READ'],
    why: 'Every staff member can look customers up.',
  },
  {
    to: '/admin/payments',
    label: 'Payments',
    icon: 'card',
    need: ['ACCOUNT_VIEW_ALL'],
    why: 'The System Admin hands out access, so they are kept away from customers’ money.',
  },
  {
    to: '/admin/ledger',
    label: 'Transactions',
    icon: 'search',
    need: ['ACCOUNT_VIEW_ALL'],
    why: 'The System Admin hands out access, so they are kept away from customers’ money.',
  },
  {
    to: '/admin/operations',
    label: 'Approvals',
    icon: 'queue',
    need: ['LEDGER_REQUEST', 'LEDGER_APPROVE'],
    why: 'Corrections belong to Operations: an Officer requests, a Supervisor approves.',
  },
  {
    to: '/admin/reconciliation',
    label: 'Reconciliation',
    icon: 'list',
    need: ['ACCOUNT_VIEW_ALL'],
    why: 'The System Admin hands out access, so they are kept away from customers’ money.',
  },
  { to: '/admin/scenarios', label: 'Scenarios', icon: 'guide', need: [], why: '' },
];

export function DeskGate({ to, children }: { to: string; children: ReactNode }) {
  const { can, claims } = useAuth();
  const item = STAFF_NAV.find((entry) => entry.to === to);
  if (!item || item.need.length === 0 || item.need.some(can)) return <>{children}</>;

  const staffRole = staffRoleOf(claims?.roles);
  return (
    <section className="panel locked-desk">
      <span className="locked-mark">
        <Icon name="lock" size={28} />
      </span>
      <h1>{item.label} is not your desk</h1>
      <p>{item.why}</p>
      {staffRole && (
        <p className="muted">
          You are signed in as a <strong>{DESKS[staffRole].title}</strong>. In a real bank this screen would simply not
          exist for you; here it is shown locked so you can see how the work is split. The API refuses it too, with{' '}
          <code>403</code>.
        </p>
      )}
      <div className="actions">
        <Link className="btn btn-primary" to="/admin">
          Back to my desk
        </Link>
        <Link className="btn btn-ghost" to="/admin/scenarios">
          Try it as the right person
        </Link>
      </div>
    </section>
  );
}

export function StaffLayout() {
  const { profile, session, claims, can, isCustomer } = useAuth();
  const signOut = useSignOut();
  const name = profile ? `${profile.firstName} ${profile.lastName}` : session?.email;
  const staffRole = staffRoleOf(claims?.roles);
  const role = staffRole ? DESKS[staffRole].title : 'Staff';
  const nav = STAFF_NAV.map((item) => ({ ...item, show: item.need.length === 0 || item.need.some(can) }));

  return (
    <div className="shell shell-staff">
      <PreviewBanner />
      <div className="app-frame">
        <aside className="sidebar sidebar-staff">
          <Brand to="/admin" subtitle="Back office" />
          <nav className="side-nav" aria-label="Back office">
            {nav.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                end={item.end}
                className={({ isActive }) => `${isActive ? 'active' : ''} ${item.show ? '' : 'locked'}`}
                title={item.show ? undefined : item.why}
              >
                <Icon name={item.show ? item.icon : 'lock'} />
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
