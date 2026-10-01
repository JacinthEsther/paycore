import { useAuth } from '../../auth/AuthContext';
import { useJourney } from '../../journey';

/** Closes the compliance loop: back to the visitor's own customer account. */
export function FinishCard() {
  const { logout } = useAuth();
  const { customerEmail, done } = useJourney();

  if (!done.includes('review')) return null;

  async function switchBack() {
    await logout('/login?as=customer');
  }

  return (
    <section className="switch-card">
      <div>
        <p className="eyebrow">Next</p>
        <h2>Bank as the customer</h2>
        <p>
          Sign out and sign back in as {customerEmail ? <strong>{customerEmail}</strong> : 'your customer'}.
          {done.includes('activate')
            ? ' Your account is active: receive money from Test Bank, then send some to a PayCore account and to another bank.'
            : ' Activate the customer’s account first (Customers → Accounts), or it cannot hold money.'}
        </p>
      </div>
      <button className="btn btn-primary btn-lg" onClick={switchBack}>
        Sign out &amp; return as customer →
      </button>
    </section>
  );
}
