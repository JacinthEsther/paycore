import { useSyncExternalStore } from 'react';

export type StepId =
  | 'register'
  | 'login'
  | 'kyc'
  | 'account'
  | 'admin-login'
  | 'review'
  | 'activate'
  | 'verified'
  | 'receive'
  | 'transfer'
  | 'transfer-out'
  | 'bill'
  | 'ops-request'
  | 'ops-approve'
  | 'scenario';

export interface Step {
  id: StepId;
  label: string;
  path: string;
}

export interface Phase {
  id: 'customer' | 'admin' | 'banking' | 'operations' | 'desks';
  title: string;
  steps: Step[];
}

export const PHASES: Phase[] = [
  {
    id: 'customer',
    title: 'Open an account',
    steps: [
      { id: 'register', label: 'Create your profile', path: '/register' },
      { id: 'login', label: 'Sign in', path: '/login' },
      { id: 'kyc', label: 'Verify your identity', path: '/app/kyc' },
      { id: 'account', label: 'Open a naira account', path: '/app' },
    ],
  },
  {
    id: 'admin',
    title: 'Compliance',
    steps: [
      { id: 'admin-login', label: 'Sign in as Compliance', path: '/login?as=staff&staff=compliance@paycore.demo' },
      { id: 'review', label: 'Approve the KYC', path: '/admin/kyc' },
      { id: 'activate', label: 'Activate the account', path: '/admin/customers' },
    ],
  },
  {
    id: 'banking',
    title: 'Bank with it',
    steps: [
      { id: 'verified', label: 'Sign back in', path: '/login?as=customer' },
      { id: 'receive', label: 'Receive money from Test Bank', path: '/app/add-money' },
      { id: 'transfer', label: 'Send to a PayCore account', path: '/app/transfer' },
      { id: 'transfer-out', label: 'Send to another bank', path: '/app/transfer' },
      { id: 'bill', label: 'Pay a bill', path: '/app/bills' },
    ],
  },
  {
    id: 'operations',
    title: 'Fix a mistake (maker-checker)',
    steps: [
      { id: 'ops-request', label: 'Officer requests a reversal', path: '/login?as=staff&staff=ops.officer@paycore.demo' },
      { id: 'ops-approve', label: 'Supervisor approves it', path: '/login?as=staff&staff=ops.supervisor@paycore.demo' },
    ],
  },
  {
    id: 'desks',
    title: 'Work every desk',
    steps: [{ id: 'scenario', label: 'Play a back-office scenario', path: '/admin/scenarios' }],
  },
];

interface JourneyState {
  done: StepId[];
  customerEmail: string | null;
}

const KEY = 'paycore.journey';
const EMPTY: JourneyState = { done: [], customerEmail: null };

let state: JourneyState = load();
const listeners = new Set<() => void>();

function load(): JourneyState {
  try {
    const raw = localStorage.getItem(KEY);
    return raw ? { ...EMPTY, ...JSON.parse(raw) } : EMPTY;
  } catch {
    return EMPTY;
  }
}

function save(next: JourneyState) {
  state = next;
  try {
    localStorage.setItem(KEY, JSON.stringify(next));
  } catch {
  }
  listeners.forEach((listener) => listener());
}

export function markDone(step: StepId) {
  if (!state.done.includes(step)) save({ ...state, done: [...state.done, step] });
}

export function rememberCustomer(email: string) {
  save({ ...state, customerEmail: email });
}

export function resetJourney() {
  save(EMPTY);
}

export function useJourney() {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    () => state,
  );
}
