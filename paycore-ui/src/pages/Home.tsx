import { Link } from 'react-router-dom';
import { resetJourney, useJourney } from '../journey';

const BUILT = [
  ['Customer registration', 'Validation, normalised phone numbers, duplicate checks'],
  ['Password identity', 'BCrypt hashes kept apart from the customer record'],
  ['Login sessions', 'Server-side sessions, logout and logout-everywhere'],
  ['JWT access + refresh tokens', 'Short-lived access tokens, rotating refresh tokens with reuse detection'],
  ['Spring Security', 'Stateless resource server that resolves the current user from the token'],
  ['RBAC', 'Roles → permissions, enforced with @PreAuthorize, with an audited role history'],
  ['KYC', 'Status workflow, BVN and NIN checks behind a provider interface, document upload, compliance review'],
  ['Abuse limits', 'BVN/NIN retry limits per customer and per network'],
  ['PostgreSQL + Flyway', 'Versioned schema migrations and UUIDv7 keys'],
  ['Automated tests', 'Unit tests plus Testcontainers integration tests'],
];

const NEXT = ['Account management', 'Ledger', 'Transactions', 'Payments'];

export function Home() {
  const { done } = useJourney();

  return (
    <div className="home">
      <section className="hero">
        <p className="eyebrow">Fintech backend engineering project</p>
        <h1>
          PayCore <span className="hero-tag">Developer Preview</span>
        </h1>
        <p className="lede">
          This is the payment infrastructure backend I'm building in Java and Spring Boot. It's still early, so this thin
          UI is here to let you try what already works instead of only reading about it: register, sign in, go through
          KYC, test the security rules, then switch to the admin account and review your own application.
        </p>
        <div className="hero-actions">
          <Link to="/register" className="btn btn-primary btn-lg">
            {done.length ? 'Continue the walkthrough' : 'Start as a customer'}
          </Link>
          <Link to="/login" className="btn btn-ghost btn-lg">
            I already have an account
          </Link>
        </div>
        <p className="muted small">
          Use made-up details. BVN and NIN checks use test records only, and any document you upload is only used
          in this demo. There's a button to generate a sample document, so you don't need to upload a real ID.
          {done.length > 0 && (
            <>
              {' '}
              <button className="link" onClick={resetJourney}>
                Restart the walkthrough
              </button>
            </>
          )}
        </p>
      </section>

      <section className="arch" aria-label="Architecture">
        <div className="arch-node">
          <strong>This UI</strong>
          <span>React · Vite</span>
        </div>
        <div className="arch-arrow" aria-hidden>
          → HTTPS + Bearer JWT →
        </div>
        <div className="arch-node arch-main">
          <strong>PayCore API</strong>
          <span>Java 21 · Spring Boot · Spring Security</span>
        </div>
        <div className="arch-arrow" aria-hidden>
          →
        </div>
        <div className="arch-node">
          <strong>PostgreSQL</strong>
          <span>Flyway migrations</span>
        </div>
        <div className="arch-arrow" aria-hidden>
          ⇢
        </div>
        <div className="arch-node">
          <strong>KycProvider</strong>
          <span>Simulated · Dojah sandbox</span>
        </div>
      </section>

      <div className="grid-2">
        <section className="card">
          <header className="card-head">
            <h2>Phase A: built and running</h2>
          </header>
          <ul className="checklist">
            {BUILT.map(([title, detail]) => (
              <li key={title}>
                <span className="check" aria-hidden>
                  ✓
                </span>
                <div>
                  <strong>{title}</strong>
                  <span className="muted">{detail}</span>
                </div>
              </li>
            ))}
          </ul>
        </section>

        <div className="stack">
          <section className="card">
            <header className="card-head">
              <h2>Currently building</h2>
            </header>
            <ol className="roadmap">
              {NEXT.map((item, i) => (
                <li key={item}>
                  <span className="roadmap-step">{i + 1}</span>
                  {item}
                  {i === 0 && <span className="badge badge-info">Next</span>}
                </li>
              ))}
            </ol>
          </section>

          <section className="card">
            <header className="card-head">
              <h2>What to look at</h2>
            </header>
            <p>
              Open the <strong>API inspector</strong> at the bottom of the page. It lists every request this UI sends,
              whether a bearer token went with it, and what the backend returned, including the 401 and 403
              responses you trigger on purpose.
            </p>
          </section>
        </div>
      </div>
    </div>
  );
}
