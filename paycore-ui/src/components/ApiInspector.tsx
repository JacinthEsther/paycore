import { useEffect, useState } from 'react';
import { clearCalls, getCalls, onCallsChange, type ApiCall } from '../api/client';
import { HttpStatus } from './ui';

/**
 * Docked panel listing every request the UI made: method, path, whether a
 * bearer token was sent, the status the backend returned and the body.
 * Passwords, BVNs and NINs are masked and tokens shortened before display.
 */
export function ApiInspector() {
  const [calls, setCalls] = useState<ApiCall[]>(getCalls);
  const [open, setOpen] = useState(false);
  const [selected, setSelected] = useState<number | null>(null);

  useEffect(() => onCallsChange(setCalls), []);

  const latest = calls[0];
  const current = calls.find((call) => call.id === selected) ?? null;

  return (
    <aside className={`inspector ${open ? 'open' : ''}`} aria-label="API inspector">
      <button className="inspector-bar" onClick={() => setOpen(!open)} aria-expanded={open}>
        <span className="inspector-title">
          <span className="pulse" aria-hidden />
          API inspector
          <span className="muted"> · {calls.length} call{calls.length === 1 ? '' : 's'}</span>
        </span>
        {latest && (
          <span className="inspector-latest">
            <code>{latest.method}</code> <code className="path">{latest.path}</code> <HttpStatus status={latest.status} />
          </span>
        )}
        <span className="chev" aria-hidden>
          {open ? '▾' : '▴'}
        </span>
      </button>

      {open && (
        <div className="inspector-body">
          <div className="inspector-list">
            <div className="inspector-tools">
              <span className="muted">Newest first</span>
              <button className="link" onClick={clearCalls}>
                Clear
              </button>
            </div>
            {calls.length === 0 && <p className="muted pad">Requests to the PayCore API will appear here.</p>}
            <ul>
              {calls.map((call) => (
                <li key={call.id}>
                  <button className={call.id === selected ? 'selected' : ''} onClick={() => setSelected(call.id)}>
                    <HttpStatus status={call.status} />
                    <code className="method">{call.method}</code>
                    <code className="path">{call.path}</code>
                    <span className={`auth auth-${call.auth === 'none' ? 'none' : 'set'}`}>{call.auth}</span>
                    <span className="ms">{call.ms} ms</span>
                  </button>
                </li>
              ))}
            </ul>
          </div>
          <div className="inspector-detail">
            {current ? (
              <>
                <p>
                  <code>
                    {current.method} {current.path}
                  </code>
                </p>
                <p className="muted">
                  Authorization: {current.auth === 'none' ? 'not sent' : `${current.auth} token`} · {current.at.toLocaleTimeString()}
                </p>
                {current.requestBody !== undefined && (
                  <>
                    <h4>Request</h4>
                    <pre>{JSON.stringify(current.requestBody, null, 2)}</pre>
                  </>
                )}
                <h4>
                  Response <HttpStatus status={current.status} />
                </h4>
                <pre>{current.responseBody === undefined ? '(empty)' : JSON.stringify(current.responseBody, null, 2)}</pre>
              </>
            ) : (
              <p className="muted pad">Select a request to see its body.</p>
            )}
          </div>
        </div>
      )}
    </aside>
  );
}
