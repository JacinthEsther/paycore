// Mirrors the Spring Boot DTOs. Instants arrive as ISO-8601 strings.

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
  /** On PAYMENT_DECLINED: the provider's reference for the declined payment. */
  providerReference?: string;
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
  duty: string;
}

export interface DemoInfo {
  enabled: boolean;
  adminEmail: string;
  /** Also the password of the shared operations officers. */
  adminPassword: string;
  /** 'SIMULATED' or 'DOJAH' */
  kycProvider: string;
  /** A shared account visitors can send money to; null until seeded. */
  recipientName: string | null;
  recipientAccountNumber: string | null;
  /** 'SIMULATED' when card top-ups are on; null otherwise. */
  fundingProvider: string | null;
  /** 'SIMULATED' when transfers to and from Test Bank are on; null otherwise. */
  bankRailProvider: string | null;
  operationsStaff: DemoStaffInfo[];
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

// ---------------------------------------------------------------------------
// Ledger. Amounts arrive as JSON numbers in major units (20000.5 = ₦20,000.50);
// the backend keeps them in kobo and never rounds.
// ---------------------------------------------------------------------------

export type TransactionType =
  | 'TRANSFER'
  | 'DEPOSIT'
  | 'INBOUND_TRANSFER'
  | 'OUTBOUND_TRANSFER'
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
  /** null for PayCore's own settlement account */
  accountNumber: string | null;
  /** The account holder; "PayCore" for its settlement account. */
  accountName: string;
  type: EntryType;
  amount: number;
  currency: string;
  createdAt: string;
}

/** The account at another bank on the other side of a bank transfer. */
export interface Counterparty {
  name: string;
  bank: string;
  accountNumber: string;
}

/** What customers see. Has no staff note, by design. */
export interface Transaction {
  id: string;
  reference: string;
  type: TransactionType;
  status: TransactionStatus;
  amount: number;
  currency: string;
  description: string | null;
  counterparty: Counterparty | null;
  /** The card processor or bank rail that carried the money. */
  provider: string | null;
  /** Its own reference: a payment reference or NIP session id. */
  providerReference: string | null;
  createdAt: string;
  postedAt: string | null;
  reversedAt: string | null;
  reversalOf: string | null;
  entries: LedgerEntry[];
}

/** What staff see: the same, plus the internal note, who initiated and who approved. */
export interface StaffTransaction extends Transaction {
  staffNote: string | null;
  initiatedBy: string | null;
  approvedBy: string | null;
}

/** A transfer to another bank, with the rail's verdict. */
export interface OutboundTransfer extends Transaction {
  transferStatus: 'SUCCESSFUL' | 'FAILED' | 'PENDING';
  failureReason: string | null;
  reversalReference: string | null;
}

/** How much more can be added to an account by card. */
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

/** The customer's own (pretend) account at Test Bank. */
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

// ---------------------------------------------------------------------------
// Operations: maker-checker corrections.
// ---------------------------------------------------------------------------

export interface OpsRequest {
  id: string;
  type: 'ADJUSTMENT' | 'REVERSAL';
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
}

