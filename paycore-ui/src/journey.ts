import { useSyncExternalStore } from 'react';

// The guided walkthrough. Progress is kept per browser in localStorage so a
// visitor can switch between the customer and the staff accounts and still
// see where they are.

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
  | 'ops-request'
  | 'ops-approve';

export interface Step {
  id: StepId;
  label: string;
  path: string;
}

export interface Phase {
  id: 'customer' | 'admin' | 'banking' | 'operations';
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
    title: 'Compliance (admin)',
    steps: [
      { id: 'admin-login', label: 'Sign in as the admin', path: '/login?as=staff' },
      { id: 'review', label: 'Approve the KYC', path: '/admin' },
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
    ],
  },
  {
    id: 'operations',
    title: 'Fix a mistake (maker-checker)',
    steps: [
      { id: 'ops-request', label: 'Officer requests a reversal', path: '/login?as=staff' },
      { id: 'ops-approve', label: 'Supervisor approves it', path: '/admin/operations' },
    ],
  },
];


interface JourneyState {
  done: StepId[];
  /** The customer account this visitor created, to sign back in later. */
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
    // Progress just won't survive a reload.
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
