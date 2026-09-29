import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { ApiInspector } from './ApiInspector';
import { JourneyBar } from './JourneyBar';

export function Layout() {
  const { session, profile, isAdmin, logout } = useAuth();
  const navigate = useNavigate();

  async function signOut() {
    await logout('/login');
    navigate('/login');
  }

  return (
    <div className="shell">
      <div className="preview-banner">
        <strong>Developer Preview</strong> · A portfolio project with sandbox data only. It is not a bank and holds no real money.
      </div>

      <header className="topbar">
        <Link to="/" className="brand" aria-label="PayCore home">
          <span className="brand-mark" aria-hidden>
            P
          </span>
          PayCore
        </Link>

        {session && (
          <nav className="topnav" aria-label="Main">
            <NavLink to="/app" end>
              Dashboard
            </NavLink>
            <NavLink to="/app/kyc">KYC</NavLink>
            <NavLink to="/app/security">Security</NavLink>
            {isAdmin && (
              <>
                <NavLink to="/admin" end>
                  Review queue
                </NavLink>
                <NavLink to="/admin/customers">Customers</NavLink>
              </>
            )}
          </nav>
        )}

        <div className="topbar-user">
          {session ? (
            <>
              <span className="who">
                {profile ? profile.firstName : session.email}
                {isAdmin && <span className="role-chip">Admin</span>}
              </span>
              <button className="btn btn-ghost btn-sm" onClick={signOut}>
                Sign out
              </button>
            </>
          ) : (
            <Link className="btn btn-ghost btn-sm" to="/login">
              Sign in
            </Link>
          )}
        </div>
      </header>

      <JourneyBar />

      <main className="main">
        <Outlet />
      </main>

      <ApiInspector />
    </div>
  );
}
