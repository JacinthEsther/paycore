import { useSyncExternalStore } from 'react';

// The guided walkthrough. Progress is kept per browser in localStorage so a
// visitor can switch between the customer and admin accounts and still see
// where they are.

export type StepId =
  | 'register'
  | 'login'
  | 'dashboard'
  | 'kyc'
  | 'security'
  | 'admin-login'
  | 'review'
  | 'roles'
  | 'verified';

export interface Step {
  id: StepId;
  label: string;
  path: string;
}

export interface Phase {
  id: 'customer' | 'admin' | 'result';
  title: string;
  steps: Step[];
}

export const PHASES: Phase[] = [
  {
    id: 'customer',
    title: 'As a customer',
    steps: [
      { id: 'register', label: 'Create account', path: '/register' },
      { id: 'login', label: 'Sign in', path: '/login' },
      { id: 'dashboard', label: 'Dashboard', path: '/app' },
      { id: 'kyc', label: 'Submit KYC', path: '/app/kyc' },
      { id: 'security', label: 'Test security', path: '/app/security' },
    ],
  },
  {
    id: 'admin',
    title: 'As an admin',
    steps: [
      { id: 'admin-login', label: 'Sign in as admin', path: '/login?as=admin' },
      { id: 'review', label: 'Review KYC', path: '/admin' },
      { id: 'roles', label: 'Roles & audit', path: '/admin/customers' },
    ],
  },
  {
    id: 'result',
    title: 'Back as the customer',
    steps: [{ id: 'verified', label: 'See the result', path: '/login?as=customer' }],
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
