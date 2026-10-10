import type { DemoScenarioResult } from './api/types';
import type { StaffRole } from './staff';

export interface ScenarioStep {
  role: StaffRole;
  action: string;
  to: string;
}

export interface Scenario {
  id: string;
  title: string;
  situation: string;
  steps: ScenarioStep[];
  lesson: string;
}

const CUSTOMER = '/admin/customers?q={email}';

export const SCENARIOS: Scenario[] = [
  {
    id: 'customer-enquiry',
    title: '“Where did my money go?”',
    situation: '{name} calls, sure their balance is wrong.',
    steps: [
      { role: 'SUPPORT', action: 'Find {name} and open Balance & statement', to: CUSTOMER },
      { role: 'SUPPORT', action: 'Open the ₦7,000 transfer to Test Bank', to: '/admin/payments?ref={reference}' },
    ],
    lesson: 'Support can see everything, and change none of it.',
  },
  {
    id: 'contact-update',
    title: 'A customer lost their SIM card',
    situation: '{name} needs a new phone number and email on their profile.',
    steps: [{ role: 'SUPPORT', action: 'Edit {name}’s contact details', to: CUSTOMER }],
    lesson: 'Support fixes details. Freezing or renaming is Compliance’s job.',
  },
  {
    id: 'kyc-review',
    title: 'A new customer is waiting',
    situation: '{name} signed up and sent their ID. Their account is pending.',
    steps: [
      { role: 'SUPPORT', action: 'Try to approve it', to: '/admin/kyc' },
      { role: 'COMPLIANCE', action: 'View the ID and approve {name}', to: '/admin/kyc?email={email}' },
      { role: 'COMPLIANCE', action: 'Activate {name}’s account', to: CUSTOMER },
    ],
    lesson: 'Only Compliance lets people in.',
  },
  {
    id: 'tier-upgrade',
    title: 'A customer wants higher limits',
    situation: '{name} sent a utility bill to move from tier 2 to tier 3.',
    steps: [
      { role: 'COMPLIANCE', action: 'Open the bill and approve', to: '/admin/kyc?email={email}' },
      { role: 'COMPLIANCE', action: 'Or freeze the account and see it flagged', to: CUSTOMER },
    ],
    lesson: 'Left alone, a clean request approves itself after 3 working days.',
  },
  {
    id: 'suspicious-activity',
    title: 'Five big credits in ten minutes',
    situation: '{name}’s new account just got 5 × ₦180,000 from other banks.',
    steps: [
      { role: 'COMPLIANCE', action: 'Check the credits and freeze the account', to: CUSTOMER },
      { role: 'SUPPORT', action: 'The customer calls: see why they’re stuck', to: CUSTOMER },
    ],
    lesson: 'Compliance freezes; Support can explain it, not undo it.',
  },
  {
    id: 'stuck-transfer',
    title: 'Debited, but the money never arrived',
    situation: '{name}’s ₦15,000.96 transfer left PayCore and the bank never confirmed it.',
    steps: [
      { role: 'SUPPORT', action: 'Find the stuck payment', to: '/admin/payments?ref={reference}' },
      { role: 'OPERATIONS_OFFICER', action: 'Check status, escalate, request Complete', to: '/admin/payments?ref={reference}' },
      { role: 'OPERATIONS_SUPERVISOR', action: 'Approve it', to: '/admin/operations' },
    ],
    lesson: 'Unknown is never treated as failed, and two people settle it.',
  },
  {
    id: 'reconciliation-day',
    title: 'Reconcile a day of payments',
    situation: '{name} made 7 payments today. 4 went wrong where PayCore couldn’t see.',
    steps: [
      { role: 'OPERATIONS_OFFICER', action: 'Run Bank rail for today', to: '/admin/reconciliation' },
      { role: 'OPERATIONS_OFFICER', action: 'Redeliver the ₦25,000.95, reverse the ₦12,000.93', to: '/admin/reconciliation' },
      { role: 'OPERATIONS_OFFICER', action: 'Run Card for today', to: '/admin/reconciliation' },
      { role: 'OPERATIONS_SUPERVISOR', action: 'Approve the reversal', to: '/admin/operations' },
      { role: 'OPERATIONS_SUPERVISOR', action: 'Close each fixed break', to: '/admin/reconciliation' },
    ],
    lesson: 'Matching PayCore’s books against the bank’s report finds every gap.',
  },
  {
    id: 'wrong-recipient',
    title: 'Sent to the wrong account',
    situation: '{name} sent ₦25,000 to a stranger, who agreed to return it.',
    steps: [
      { role: 'OPERATIONS_OFFICER', action: 'Request a reversal', to: '/admin/ledger?ref={reference}' },
      { role: 'OPERATIONS_SUPERVISOR', action: 'Approve it', to: '/admin/operations' },
    ],
    lesson: 'Nobody takes money out of an account alone.',
  },
  {
    id: 'new-hire',
    title: 'A new colleague joins Operations',
    situation: '{name} starts today and needs access.',
    steps: [
      { role: 'ADMIN', action: 'Make {name} an Operations Officer', to: CUSTOMER },
      { role: 'ADMIN', action: 'Try making them Supervisor too', to: CUSTOMER },
    ],
    lesson: 'One person can’t both request and approve.',
  },
];

export function fill(text: string, result: DemoScenarioResult | undefined) {
  const values: Record<string, string> = result
    ? {
        name: result.customerName,
        email: encodeURIComponent(result.customerEmail),
        account: result.accountNumber ?? '',
        reference: encodeURIComponent(result.reference ?? ''),
      }
    : { name: 'the customer' };
  const filled = text.replace(/\{(\w+)\}/g, (_, key) => values[key] ?? '');
  return filled.charAt(0).toUpperCase() + filled.slice(1);
}

const KEY = 'paycore.scenarios';

export function loadResults(): Record<string, DemoScenarioResult> {
  try {
    return JSON.parse(localStorage.getItem(KEY) ?? '{}');
  } catch {
    return {};
  }
}

export function saveResult(result: DemoScenarioResult) {
  try {
    localStorage.setItem(KEY, JSON.stringify({ ...loadResults(), [result.scenario]: result }));
  } catch {
  }
}
