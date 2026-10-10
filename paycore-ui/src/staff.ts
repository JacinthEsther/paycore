export type StaffRole = 'SUPPORT' | 'COMPLIANCE' | 'OPERATIONS_OFFICER' | 'OPERATIONS_SUPERVISOR' | 'ADMIN';

export const STAFF_ROLES: StaffRole[] = ['SUPPORT', 'COMPLIANCE', 'OPERATIONS_OFFICER', 'OPERATIONS_SUPERVISOR', 'ADMIN'];

export const ASSIGNABLE_ROLES = ['CUSTOMER', ...STAFF_ROLES];

export interface Duty {
  text: string;
  scenario: string;
}

export interface Desk {
  title: string;
  realLife: string;
  does: Duty[];
  cannot: string[];
}

export const DESKS: Record<StaffRole, Desk> = {
  SUPPORT: {
    title: 'Customer Support',
    realLife:
      'Answers customers when they call or chat. Sees everything; changes only contact details.',
    does: [
      { text: 'Look up any customer, their accounts, transactions and payments', scenario: 'customer-enquiry' },
      { text: 'Update a customer’s email or phone number (name changes need proof and go through Compliance)', scenario: 'contact-update' },
      { text: 'Tell the customer what is happening with a stuck payment', scenario: 'stuck-transfer' },
    ],
    cannot: [
      'Approve KYC, activate, freeze or suspend: that is Compliance’s decision',
      'Reverse or adjust money: that needs two Operations staff',
      'Give anyone a staff role',
    ],
  },
  COMPLIANCE: {
    title: 'Compliance Officer',
    realLife:
      'Decides who may bank with PayCore, at what limits, and acts when something looks wrong.',
    does: [
      { text: 'Review KYC: check the BVN result and the ID document, then approve, reject or ask for more', scenario: 'kyc-review' },
      { text: 'Approve tier upgrades, which raise the customer’s limits', scenario: 'tier-upgrade' },
      { text: 'Activate new accounts once KYC is verified', scenario: 'kyc-review' },
      { text: 'Freeze an account or suspend a customer when something looks wrong', scenario: 'suspicious-activity' },
    ],
    cannot: [
      'Move, reverse or adjust money',
      'Give anyone a staff role',
    ],
  },
  OPERATIONS_OFFICER: {
    title: 'Operations Officer',
    realLife:
      'Investigates payments that went wrong and prepares the fix: the “maker”.',
    does: [
      { text: 'Check stuck payments with the bank and escalate them', scenario: 'stuck-transfer' },
      { text: 'Run reconciliation against the bank’s and card processor’s reports', scenario: 'reconciliation-day' },
      { text: 'Request reversals, adjustments and payment resolutions, each with a reason', scenario: 'wrong-recipient' },
    ],
    cannot: [
      'Approve any request, including their own: that is the Supervisor',
      'Review KYC or change account status',
    ],
  },
  OPERATIONS_SUPERVISOR: {
    title: 'Operations Supervisor',
    realLife:
      'Checks the officer’s fix before any money moves: the “checker”.',
    does: [
      { text: 'Approve or reject the officers’ reversals, adjustments and payment resolutions', scenario: 'wrong-recipient' },
      { text: 'Close reconciliation breaks once they are fixed', scenario: 'reconciliation-day' },
    ],
    cannot: [
      'Start a request: if the supervisor could, they could approve their own idea',
      'Review KYC or change account status',
    ],
  },
  ADMIN: {
    title: 'System Admin',
    realLife:
      'Manages who works here and what they may do, with no power over money.',
    does: [
      { text: 'Give staff their roles and take them away, always with a reason in the audit trail', scenario: 'new-hire' },
      { text: 'See every role change ever made', scenario: 'new-hire' },
    ],
    cannot: [
      'Change their own roles',
      'Give one person two duties that must stay apart, e.g. Officer and Supervisor',
      'See balances, review KYC or move money',
    ],
  },
};

export function roleLabel(role: string) {
  return role in DESKS ? DESKS[role as StaffRole].title : role.charAt(0) + role.slice(1).toLowerCase();
}

export function staffRoleOf(roles: string[] | undefined): StaffRole | null {
  return STAFF_ROLES.find((role) => roles?.includes(role)) ?? null;
}
