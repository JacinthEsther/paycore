export type CustomerStatus = 'PENDING_VERIFICATION' | 'ACTIVE' | 'SUSPENDED' | 'CLOSED';

export type KycStatus =
  | 'NOT_STARTED'
  | 'IN_PROGRESS'
  | 'SUBMITTED'
  | 'UNDER_REVIEW'
  | 'ADDITIONAL_INFO_REQUIRED'
  | 'VERIFIED'
  | 'REJECTED';

export type KycDocumentType =
  | 'NATIONAL_ID'
  | 'PASSPORT'
  | 'DRIVERS_LICENSE'
  | 'VOTERS_CARD'
  | 'PROOF_OF_ADDRESS';

export interface ApiErrorBody {
  status?: number;
  error?: string;
  message?: string;
  missing?: string[];
  retryAfter?: string;
  providerReference?: string;
  attemptsRemaining?: number;
  limit?: string;
  remaining?: number;
  resetsAt?: string;
}

export type DeviceStatus = 'PENDING' | 'TRUSTED' | 'REVOKED';

export interface Device {
  id: string;
  label: string | null;
  status: DeviceStatus;
  current: boolean;
  createdAt: string;
  lastSeenAt: string;
  trustedAt: string | null;
  coolingOffUntil: string | null;
}

export interface PinStatus {
  set: boolean;
  locked: boolean;
  attemptsRemaining: number;
}

export interface CodeSent {
  sentTo: string;
  expiresAt: string;
}

export interface LoginResponse {
  customerId: string;
  email: string;
  status: CustomerStatus;
  sessionId: string;
  sessionToken: string;
  sessionExpiresAt: string;
  tokenType: string;
  accessToken: string;
  accessTokenExpiresAt: string;
  refreshToken: string;
  refreshTokenExpiresAt: string;
  message: string;
}

export interface TokenResponse {
  tokenType: string;
  accessToken: string;
  accessTokenExpiresAt: string;
  refreshToken: string;
  refreshTokenExpiresAt: string;
}

export interface Customer {
  id: string;
  firstName: string;
  lastName: string;
  email: string;
  phoneNumber: string;
  status: CustomerStatus;
  emailVerified: boolean;
  phoneVerified: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface Kyc {
  id: string;
  customerId: string;
  status: KycStatus;
  reviewReason: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface KycVerification {
  kycId: string;
  result: 'PENDING' | 'PASSED' | 'FAILED' | 'REQUIRES_REVIEW';
  status: KycStatus;
  provider: string;
  reason: string | null;
  remainingAttempts: number;
}

export interface KycDocument {
  id: string;
  kycId: string;
  documentType: KycDocumentType;
  kycStatus: KycStatus;
  createdAt: string;
}

export interface PageInfo {
  number: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
}

export interface KycReviewItem {
  kycId: string;
  customerId: string;
  customerName: string;
  customerEmail: string;
  status: KycStatus;
  reviewReason: string | null;
  bvnPassed: boolean;
  ninPassed: boolean;
  documentTypes: KycDocumentType[];
  createdAt: string;
  updatedAt: string;
}

export interface KycReviewQueue {
  profiles: KycReviewItem[];
  page: PageInfo;
}

export interface BvnAttempt {
  id: string;
  result: string;
  provider: string;
  reason: string | null;
  ipAddress: string | null;
  createdAt: string;
  countsTowardLimit: boolean;
}

export interface BvnAttempts {
  kycId: string;
  kycStatus: KycStatus;
  maxFailedAttempts: number;
  attemptWindow: string;
  failedAttemptsCounted: number;
  remainingAttempts: number;
  limited: boolean;
  retryAfter: string | null;
  resetAt: string | null;
  attempts: BvnAttempt[];
  page: PageInfo;
}

export interface CustomerSummary {
  id: string;
  firstName: string;
  lastName: string;
  email: string;
  status: CustomerStatus;
  roles: string[];
  createdAt: string;
}

export interface CustomerPage {
  customers: CustomerSummary[];
  page: PageInfo;
}

export interface CustomerRoles {
  customerId: string;
  roles: string[];
}

export interface RoleEvent {
  id: string;
  role: string;
  action: 'ASSIGNED' | 'REVOKED';
  performedBy: string | null;
  reason: string | null;
  occurredAt: string;
}

export interface DemoStaffInfo {
  email: string;
  name: string;
  role: string;
  title: string;
  duty: string;
}

export interface KycDocumentSummary {
  id: string;
  documentType: KycDocumentType;
  documentNumber: string | null;
  uploadedAt: string;
}

export interface DemoScenarioResult {
  scenario: string;
  customerName: string;
  customerEmail: string;
  accountNumber: string | null;
  reference: string | null;
  summary: string;
}

export interface DemoInfo {
  enabled: boolean;
  staffPassword: string;
  kycProvider: string;
  recipientName: string | null;
  recipientAccountNumber: string | null;
  fundingProvider: string | null;
  bankRailProvider: string | null;
  staff: DemoStaffInfo[];
}

export type AccountStatus = 'PENDING' | 'ACTIVE' | 'FROZEN' | 'CLOSED';

export interface Account {
  id: string;
  customerId: string;
  accountNumber: string;
  type: 'PERSONAL';
  status: AccountStatus;
  currency: string;
  createdAt: string;
  updatedAt: string;
  closedAt: string | null;
}

export interface AccountEvent {
  id: string;
  eventType: 'OPENED' | 'ACTIVATED' | 'FROZEN' | 'UNFROZEN' | 'CLOSED';
  fromStatus: AccountStatus | null;
  toStatus: AccountStatus;
  performedBy: string;
  reason: string | null;
  occurredAt: string;
}

export type TransactionType =
  | 'TRANSFER'
  | 'DEPOSIT'
  | 'INBOUND_TRANSFER'
  | 'OUTBOUND_TRANSFER'
  | 'BILL_PAYMENT'
  | 'ADJUSTMENT'
  | 'REVERSAL'
  | 'WITHDRAWAL';
export type TransactionStatus = 'INITIATED' | 'POSTED' | 'REVERSED';
export type EntryType = 'DEBIT' | 'CREDIT';

export interface Balance {
  accountId: string;
  accountNumber: string;
  balance: number;
  currency: string;
}

export interface LedgerEntry {
  id: string;
  accountId: string;
  accountNumber: string | null;
  accountName: string;
  type: EntryType;
  amount: number;
  currency: string;
  createdAt: string;
}

export interface Counterparty {
  name: string;
  bank: string;
  accountNumber: string;
}

export interface Transaction {
  id: string;
  reference: string;
  type: TransactionType;
  status: TransactionStatus;
  amount: number;
  currency: string;
  description: string | null;
  counterparty: Counterparty | null;
  provider: string | null;
  providerReference: string | null;
  createdAt: string;
  postedAt: string | null;
  reversedAt: string | null;
  reversalOf: string | null;
  entries: LedgerEntry[];
}

export interface StaffTransaction extends Transaction {
  staffNote: string | null;
  initiatedBy: string | null;
  approvedBy: string | null;
}

export interface OutboundTransfer extends Transaction {
  transferStatus: 'SUCCESSFUL' | 'FAILED' | 'PENDING';
  failureReason: string | null;
  reversalReference: string | null;
  paymentId: string;
  paymentReference: string;
  paymentStatus: PaymentStatus;
}

export type PaymentStatus = 'CREATED' | 'PROCESSING' | 'UNKNOWN' | 'NEEDS_REVIEW' | 'SUCCEEDED' | 'FAILED';

export interface Payment {
  id: string;
  reference: string;
  type: 'OUTBOUND_TRANSFER' | 'CARD_FUNDING' | 'BILL_PAYMENT';
  status: PaymentStatus;
  accountId: string;
  amount: number;
  currency: string;
  provider: string;
  externalReference: string;
  providerReference: string | null;
  narration: string | null;
  counterparty: Counterparty | null;
  ledgerTransactionId: string | null;
  reversalTransactionId: string | null;
  failureReason: string | null;
  refundReference: string | null;
  statusChecks: number;
  nextCheckAt: string | null;
  createdAt: string;
  updatedAt: string;
  completedAt: string | null;
  bill: { billerId: string; productCode: string | null; productName: string | null; token: string | null } | null;
}

export interface PaymentEvent {
  fromStatus: PaymentStatus | null;
  toStatus: PaymentStatus;
  statusChecks: number;
  failureReason: string | null;
  at: string;
}

export interface PaymentPage {
  payments: Payment[];
  page: { number: number; size: number; totalElements: number; totalPages: number; hasNext: boolean };
}

export type PaymentResolution = 'COMPLETE' | 'REVERSE' | 'CREDIT' | 'REFUND' | 'CLOSE';

export interface FundingAllowance {
  accountId: string;
  enabled: boolean;
  provider: string | null;
  currency: string;
  limit: number;
  funded: number;
  remaining: number;
}

export interface Bank {
  code: string;
  name: string;
}

export interface NameEnquiry {
  bankCode: string;
  bankName: string;
  accountNumber: string;
  accountName: string;
}

export interface TestBankAccount {
  bankCode: string;
  bankName: string;
  accountNumber: string;
  accountName: string;
  currency: string;
  limitPerAccount: number;
  remaining: number;
}

export interface StatementLine {
  postedAt: string;
  transactionId: string;
  reference: string;
  transactionType: TransactionType;
  transactionStatus: TransactionStatus;
  description: string | null;
  counterpartyName: string | null;
  counterpartyBank: string | null;
  direction: EntryType;
  amount: number;
  balanceAfter: number;
}

export interface Statement {
  accountId: string;
  accountNumber: string;
  currency: string;
  from: string;
  to: string;
  timeZone: string;
  openingBalance: number;
  totalCredits: number;
  totalDebits: number;
  closingBalance: number;
  lines: StatementLine[];
  page: PageInfo;
}

export interface SystemAccount {
  accountId: string;
  type: 'SETTLEMENT';
  currency: string;
  balance: number;
}

export interface OpsRequest {
  id: string;
  type: 'ADJUSTMENT' | 'REVERSAL' | 'PAYMENT_RESOLUTION';
  status: 'PENDING' | 'APPROVED' | 'REJECTED';
  accountId: string | null;
  accountNumber: string | null;
  accountName: string | null;
  direction: EntryType | null;
  transactionId: string | null;
  transactionReference: string | null;
  transactionDescription: string | null;
  amount: number;
  currency: string;
  reason: string;
  customerDescription: string | null;
  requestedBy: string;
  requestedByName: string | null;
  requestedAt: string;
  decidedBy: string | null;
  decidedByName: string | null;
  decidedAt: string | null;
  decisionNote: string | null;
  resultTransactionId: string | null;
  resultReference: string | null;
  paymentId: string | null;
  paymentReference: string | null;
  paymentType: Payment['type'] | null;
  resolution: PaymentResolution | null;
}

export type ReconciliationResult = 'MATCHED' | 'MISSING_LOCAL' | 'MISSING_PROVIDER' | 'AMOUNT_MISMATCH' | 'STATUS_MISMATCH';

export interface ReconciliationRun {
  id: string;
  providerKind: 'BANK_RAIL' | 'CARD';
  provider: string;
  businessDate: string;
  windowStart: string;
  windowEnd: string;
  scheduled: boolean;
  providerLines: number;
  paycoreRecords: number;
  matched: number;
  breaks: number;
  providerTotal: number;
  paycoreTotal: number;
  createdAt: string;
}

export interface ReconciliationItem {
  id: string;
  kind: 'OUTBOUND' | 'INBOUND' | 'CARD';
  externalReference: string;
  result: ReconciliationResult;
  currency: string;
  providerAmount: number | null;
  providerStatus: string | null;
  paycoreAmount: number | null;
  paycoreStatus: string | null;
  paymentId: string | null;
  ledgerTransactionId: string | null;
  accountId: string | null;
  detail: string | null;
  resolutionStatus: 'NOT_REQUIRED' | 'OPEN' | 'RESOLVED';
  resolvedBy: string | null;
  resolvedAt: string | null;
  resolutionNote: string | null;
}

export interface ReconciliationRunDetail {
  run: ReconciliationRun;
  items: ReconciliationItem[];
}

export type KycTierUpgradeStatus = 'PENDING' | 'APPROVED' | 'REJECTED';

export interface KycTier {
  tier: number;
  currency: string;
  dailySend: number;
  sentToday: number;
  maxBalance: number | null;
  resetsAt: string;
  nextTier: number | null;
  nextTierNeeds: string[];
  upgrade: {
    requestedTier: number;
    status: KycTierUpgradeStatus;
    reason: string | null;
    requestedAt: string;
    decidedAt: string | null;
    reviewDueAt: string;
    needsCloserLook: boolean;
    decidedAutomatically: boolean;
  } | null;
  tiers: { tier: number; dailySend: number; maxBalance: number | null; requirement: string }[];
  reviewWorkingDays: number;
}

export interface KycTierUpgrade {
  id: string;
  kycId: string;
  customerId: string;
  customerName: string;
  customerEmail: string;
  currentTier: number;
  requestedTier: number;
  documentId: string;
  documentType: KycDocumentType;
  status: KycTierUpgradeStatus;
  reason: string | null;
  requestedAt: string;
  decidedAt: string | null;
  reviewDueAt: string;
  flags: string[];
  decidedAutomatically: boolean;
}

export type BillCategory = 'AIRTIME' | 'DATA' | 'ELECTRICITY' | 'TV';

export interface Biller {
  id: string;
  name: string;
  category: BillCategory;
  numberLabel: string;
  products: boolean;
  verified: boolean;
  meterTypes: string[] | null;
  minAmount: number | null;
  maxAmount: number | null;
}

export interface BillProduct {
  code: string;
  name: string;
  amount: number;
}

export interface BillCustomer {
  billerId: string;
  customerNumber: string;
  name: string;
  address: string | null;
}

export interface BillPayment extends Transaction {
  billStatus: 'SUCCESSFUL' | 'FAILED' | 'PENDING';
  billerId: string;
  productName: string | null;
  customerNumber: string;
  token: string | null;
  failureReason: string | null;
  reversalReference: string | null;
  paymentId: string;
  paymentReference: string;
  paymentStatus: PaymentStatus;
}
