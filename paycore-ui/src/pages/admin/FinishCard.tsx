import { useAuth } from '../../auth/AuthContext';
import { useJourney } from '../../journey';

/** Closes the loop: back to the visitor's own customer account. */
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
        <p className="eyebrow">Last step</p>
        <h2>See your decision as the customer</h2>
        <p>
          Sign out of the admin account and sign back in as {customerEmail ? <strong>{customerEmail}</strong> : 'your customer'}. The
          KYC page will show the status you just set.
        </p>
      </div>
      <button className="btn btn-primary btn-lg" onClick={switchBack}>
        Sign out &amp; return as customer →
      </button>
    </section>
  );
}
