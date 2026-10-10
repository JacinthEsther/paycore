import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../../api/client';
import type { KycTierUpgrade, OpsRequest, PaymentPage } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { ScreenTitle } from '../../components/Layout';
import { Card } from '../../components/ui';
import { DESKS, staffRoleOf, type StaffRole } from '../../staff';

interface QueueItem {
  label: string;
  to: string;
  count: () => Promise<number>;
}

const total = (path: string) => api<{ page: { totalElements: number } }>(path).then((r) => r.page.totalElements);

const QUEUES: Record<StaffRole, QueueItem[]> = {
  SUPPORT: [],
  COMPLIANCE: [
    {
      label: 'KYC submissions to review',
      to: '/admin/kyc',
      count: async () =>
        (await total('/api/v1/kyc/reviews?size=1&status=SUBMITTED')) +
        (await total('/api/v1/kyc/reviews?size=1&status=UNDER_REVIEW')),
    },
    {
      label: 'Tier upgrade requests',
      to: '/admin/kyc',
      count: () => api<KycTierUpgrade[]>('/api/v1/kyc/tier-upgrades').then((list) => list.length),
    },
  ],
  OPERATIONS_OFFICER: [
    {
      label: 'Payments with an unknown outcome',
      to: '/admin/payments?status=UNKNOWN',
      count: () => api<PaymentPage>('/api/v1/admin/payments?status=UNKNOWN').then((r) => r.page.totalElements),
    },
    {
      label: 'Payments escalated for a decision',
      to: '/admin/payments?status=NEEDS_REVIEW',
      count: () => api<PaymentPage>('/api/v1/admin/payments?status=NEEDS_REVIEW').then((r) => r.page.totalElements),
    },
  ],
  OPERATIONS_SUPERVISOR: [
    {
      label: 'Requests waiting for your approval',
      to: '/admin/operations',
      count: () => api<OpsRequest[]>('/api/v1/ops/requests').then((list) => list.length),
    },
  ],
  ADMIN: [],
};

const NO_QUEUE: Partial<Record<StaffRole, string>> = {
  SUPPORT: 'Your work arrives as calls and chats. When a customer gets in touch, find them under Customers.',
  ADMIN: 'Your work arrives when someone joins, moves or leaves. Find them under Customers to change their roles.',
};

export function MyDesk() {
  const { claims, profile } = useAuth();
  const role = staffRoleOf(claims?.roles);
  const [counts, setCounts] = useState<(number | null)[]>([]);

  useEffect(() => {
    if (!role) return;
    let cancelled = false;
    Promise.all(QUEUES[role].map((item) => item.count().catch(() => null))).then(
      (values) => !cancelled && setCounts(values),
    );
    return () => {
      cancelled = true;
    };
  }, [role]);

  if (!role) return <p className="muted">This account has no staff role.</p>;

  const desk = DESKS[role];
  return (
    <>
      <ScreenTitle title={`${desk.title}${profile ? ` · ${profile.firstName}` : ''}`}>{desk.realLife}</ScreenTitle>

      <div className="desk-grid">
        <Card title="Your job">
          <ul className="desk-list does">
            {desk.does.map((duty) => (
              <li key={duty.text}>
                {duty.text}{' '}
                <Link className="small" to={`/admin/scenarios#${duty.scenario}`}>
                  Practise ›
                </Link>
              </li>
            ))}
          </ul>
        </Card>

        <Card title="Not your job">
          <ul className="desk-list cannot">
            {desk.cannot.map((item) => (
              <li key={item}>{item}</li>
            ))}
          </ul>
          <p className="muted small">
            Enforced by the API, not just hidden.
          </p>
        </Card>

        <Card title="Waiting for you">
          {QUEUES[role].length === 0 ? (
            <p className="muted">{NO_QUEUE[role]}</p>
          ) : (
            <ul className="desk-list">
              {QUEUES[role].map((item, i) => (
                <li key={item.label}>
                  <Link to={item.to}>
                    <span className="queue-count">{counts[i] ?? '·'}</span> {item.label}
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </Card>
      </div>
    </>
  );
}
