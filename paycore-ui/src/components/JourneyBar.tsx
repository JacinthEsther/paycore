import { Link, useLocation } from 'react-router-dom';
import { PHASES, useJourney } from '../journey';

/**
 * The guided walkthrough: customer phase, then admin phase, then back to
 * the customer to see the decision. Completed steps are ticked.
 */
export function JourneyBar() {
  const { done } = useJourney();
  const { pathname } = useLocation();
  const next = PHASES.flatMap((phase) => phase.steps).find((step) => !done.includes(step.id));

  return (
    <nav className="journey" aria-label="Demo walkthrough">
      {PHASES.map((phase, phaseIndex) => (
        <div className="journey-phase" key={phase.id}>
          <span className="journey-phase-title">
            <span className="journey-phase-num">{phaseIndex + 1}</span>
            {phase.title}
          </span>
          <ol>
            {phase.steps.map((step) => {
              const isDone = done.includes(step.id);
              const isNext = next?.id === step.id;
              const isHere = pathname === step.path.split('?')[0];
              return (
                <li key={step.id} className={`${isDone ? 'done' : ''} ${isNext ? 'next' : ''} ${isHere ? 'here' : ''}`}>
                  <Link to={step.path}>
                    <span className="tick" aria-hidden>
                      {isDone ? '✓' : ''}
                    </span>
                    {step.label}
                    {isDone && <span className="sr-only"> (done)</span>}
                    {isNext && <span className="sr-only"> (next)</span>}
                  </Link>
                </li>
              );
            })}
          </ol>
        </div>
      ))}
    </nav>
  );
}
