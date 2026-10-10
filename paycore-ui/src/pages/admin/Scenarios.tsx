import { useEffect, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { api, errorMessage } from '../../api/client';
import { useDemoInfo } from '../../api/demo';
import type { DemoScenarioResult } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { ScreenTitle } from '../../components/Layout';
import { Notice } from '../../components/ui';
import { markDone } from '../../journey';
import { fill, loadResults, saveResult, SCENARIOS, type Scenario, type ScenarioStep } from '../../scenarios';
import { DESKS, staffRoleOf } from '../../staff';

export function Scenarios() {
  const demo = useDemoInfo();
  const { hash } = useLocation();
  const [results, setResults] = useState<Record<string, DemoScenarioResult>>(loadResults);

  useEffect(() => {
    if (hash) document.getElementById(hash.slice(1))?.scrollIntoView({ block: 'start' });
  }, [hash]);

  if (demo === null) {
    return (
      <>
        <ScreenTitle title="Scenarios" />
        <Notice tone="warn">Scenarios need demo mode, which is off on this server.</Notice>
      </>
    );
  }

  return (
    <>
      <ScreenTitle title="Scenarios">Start one, then follow the steps. Each button takes you to the right screen as the right person.</ScreenTitle>

      <div className="scenario-list">
        {SCENARIOS.map((scenario) => (
          <ScenarioCard
            key={scenario.id}
            scenario={scenario}
            result={results[scenario.id]}
            onCreated={(result) => {
              saveResult(result);
              setResults(loadResults());
              markDone('scenario');
            }}
          />
        ))}
      </div>
    </>
  );
}

function ScenarioCard({
  scenario,
  result,
  onCreated,
}: {
  scenario: Scenario;
  result: DemoScenarioResult | undefined;
  onCreated: (result: DemoScenarioResult) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function start() {
    setBusy(true);
    setError(null);
    try {
      onCreated(await api<DemoScenarioResult>(`/api/v1/demo/scenarios/${scenario.id}`, { method: 'POST' }));
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section id={scenario.id} className="panel scenario">
      <header className="scenario-head">
        <div>
          <h2>{scenario.title}</h2>
          <p className="muted small">{fill(scenario.situation, result)}</p>
        </div>
        <button className={`btn btn-sm ${result ? 'btn-ghost' : 'btn-primary'}`} onClick={start} disabled={busy}>
          {busy ? 'Starting…' : result ? 'Start again' : 'Start'}
        </button>
      </header>

      {error && <Notice tone="bad">{error}</Notice>}

      <ol className="scenario-steps">
        {scenario.steps.map((step, i) => (
          <Step key={i} step={step} result={result} />
        ))}
      </ol>

      <p className="scenario-lesson small">{scenario.lesson}</p>
    </section>
  );
}

function Step({ step, result }: { step: ScenarioStep; result: DemoScenarioResult | undefined }) {
  const demo = useDemoInfo();
  const { claims, logout } = useAuth();
  const navigate = useNavigate();
  const mine = staffRoleOf(claims?.roles) === step.role;
  const member = demo?.staff.find((staff) => staff.role === step.role);
  const target = fill(step.to, result);

  async function switchTo() {
    if (!member) return;
    const login = `/login?as=staff&staff=${encodeURIComponent(member.email)}&next=${encodeURIComponent(target)}`;
    await logout(login);
    navigate(login);
  }

  return (
    <li className={mine ? 'mine' : ''}>
      <span className="chip">{DESKS[step.role].title}</span>
      <span className="scenario-action">{fill(step.action, result)}</span>
      {!result ? (
        <button className="btn btn-ghost btn-sm" disabled>
          Start first
        </button>
      ) : mine ? (
        <Link className="btn btn-primary btn-sm" to={target}>
          Go →
        </Link>
      ) : (
        <button className="btn btn-ghost btn-sm" onClick={switchTo}>
          Sign in as {member?.name.split(' ')[0] ?? DESKS[step.role].title} →
        </button>
      )}
    </li>
  );
}
