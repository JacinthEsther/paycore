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

export interface DemoInfo {
  enabled: boolean;
  adminEmail: string;
  adminPassword: string;
  /** 'SIMULATED' or 'DOJAH' */
  kycProvider: string;
}
