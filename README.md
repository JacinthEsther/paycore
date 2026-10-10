# PayCore

PayCore is the backend for a Nigerian financial platform. It covers the parts every
fintech needs before money can move: customer onboarding, secure sign-in,
role-based access control, KYC (Know Your Customer) identity verification,
customer accounts, a double-entry ledger, money in and out through payment
rails (card top-ups, transfers to and from other banks), and maker-checker
corrections. Nobody, staff included, can put money into an account by typing
an amount.

The repository also contains **paycore-ui**, a small React app that lets you
click through the API and see each request and response as it happens.

## Features

- **Customer onboarding:** registration with validated Nigerian phone numbers,
  profile updates, and suspend, reactivate or close an account.
- **Authentication:** email and password login with short-lived JWT access
  tokens and rotating refresh tokens. Sessions sign out after 15 minutes of
  inactivity and after 12 hours at most, and customers can log out of all devices.
- **Roles and separation of duties:** customers, plus five staff desks, each
  with only the permissions its job needs, checked on every endpoint:

  | Role | Does | Cannot |
  | --- | --- | --- |
  | `SUPPORT` | Looks up customers, accounts and payments; edits contact details | Change status, review KYC, move money |
  | `COMPLIANCE` | Reviews KYC and tier upgrades (with the documents), activates, freezes, suspends, closes | Move money, manage roles |
  | `OPERATIONS_OFFICER` | Investigates payments, runs reconciliation, requests corrections (maker) | Approve anything |
  | `OPERATIONS_SUPERVISOR` | Approves or rejects corrections, closes reconciliation breaks (checker) | File requests |
  | `ADMIN` | Assigns and revokes roles, with a reason in an audit history | See money, review KYC, change their own roles |

  Conflicting roles cannot be held together (officer + supervisor, or admin +
  any other staff role), and staff accounts cannot be edited, suspended or
  closed from the customer desk.
- **KYC verification:**
  - BVN and NIN checks through [Dojah](https://dojah.io), or a built-in
    simulated provider for demos
  - Document upload (PDF, PNG, JPEG)
  - A review queue where staff approve, reject or request more information
  - Limits on failed BVN attempts, per customer and per network
  - Raw BVN and NIN numbers are never stored
- **Accounts:**
  - Customers open a personal NGN account, which starts `PENDING`, and see
    only their own accounts; the owner always comes from the access token
  - Admins activate it (`PENDING` → `ACTIVE`) once the customer's KYC is
    verified, so account, customer and KYC status stay independent
  - 10-digit account numbers with a NUBAN check digit, generated with
    `SecureRandom` and guaranteed unique by the database
  - One open account per customer, type and currency, enforced by a
    database constraint
  - Admins activate, freeze, unfreeze and close accounts with a required
    reason; every change goes into an append-only audit history, and admins
    cannot act on their own accounts
  - No balance column on the account: balances are derived from the ledger
- **Ledger:**
  - Double-entry: every transaction is a debit and an equal credit, entries
    are never edited, and balances are credits minus debits
  - Customer transfers by account number, with idempotency keys and row
    locks so the same money can never be spent twice
  - Every customer balance is matched by PayCore's per-currency settlement
    account (the money it holds at its bank)
  - Reversals: a new transaction undoes the original, which is kept and
    marked `REVERSED`; nothing is reversed twice
  - Staff notes are internal and kept apart from the descriptions
    customers see
  - PostgreSQL enforces the invariants too: every transaction balances,
    entries are append-only, and a transaction's status only moves forward
- **Money in and out:**
  - Inbound transfers from other banks through a bank-rail webhook signed
    with HMAC-SHA256, idempotent by NIP-style session id
  - Outbound transfers to other banks with name enquiry; a payment the rail
    rejects is reversed automatically
  - Card top-ups, credited only once the processor confirms the payment
  - Simulated rail ("Test Bank") and card processor behind the same
    interfaces a real provider would implement
- **Payments:** every transfer out and card top-up is a payment with its
  own state machine (`CREATED → PROCESSING → SUCCEEDED / FAILED`, or
  `UNKNOWN` when the provider gives no answer). An unknown outcome is never
  assumed to have failed: a background job asks the provider (status
  query), with backoff, and hands the payment to operations
  (`NEEDS_REVIEW`) if it never gets an answer. Payments interrupted by a
  crash are found and settled the same way. Every status change is
  recorded by the database.
  Payments automation cannot settle are resolved by operations under
  maker-checker (complete or reverse a transfer; credit, refund or close a
  top-up), and never against a definite answer from the provider.
- **Transaction PIN and device binding:**
  - Every payment needs the customer's 4-digit transaction PIN
    (`X-Transaction-PIN`); 3 wrong tries lock it until it is reset with an
    email code
  - A new device is confirmed with an email code, and for its first 24 hours
    it can send at most NGN 20,000 in total
- **Limits by KYC tier:** each tier has a daily send limit and a maximum
  balance (tier 1: NGN 50,000 / 300,000; tier 2: NGN 200,000 / 500,000;
  tier 3: NGN 5,000,000 / no cap), checked in the ledger under the account
  lock. Tier 2 comes as soon as both BVN and NIN are verified. Tier 3 needs
  a proof of address and is decided within 3 working days (weekends skipped):
  compliance can approve or decline it sooner, and if nothing has changed by
  the deadline (customer suspended or closed, an account frozen, KYC no longer
  verified, an upgrade declined in the last 30 days) a scheduled job approves
  it automatically; otherwise it is flagged and waits for a person.
- **Bill payments:** airtime, data, electricity and TV through a bills
  provider (VTpass sandbox, or a simulated one for demos). The customer is
  debited first; if the biller declines, the debit is reversed, and an
  unknown outcome is queried like any other payment. Electricity returns the
  meter token.
- **Reconciliation:** each provider's daily settlement report is matched
  against PayCore's books in both directions (matched, missing locally, missing
  at the provider, amount or status mismatch). It runs every morning for
  yesterday and on demand; breaks are fixed through the usual controls (the rail
  re-delivering a lost notification, maker-checker reversals) and closed by a
  supervisor. Runs are evidence the database will not let anyone rewrite.
- **Database privileges:** in production Flyway migrates as the owner and the
  API runs as `paycore_app`, which cannot delete or truncate ledger and payment
  rows, rewrite entries, disable triggers or change the schema; the API can
  refuse to start otherwise (`scripts/db/create-app-role.sql`).
- **Operations (maker-checker):** reversals and manual adjustments are
  requested by an Operations Officer and posted only when a Supervisor
  approves; the database enforces the four-eyes rule too. Nobody else can
  move money.
  - Statements with opening, closing and running balances per calendar day
    in Lagos time
- **API docs:** Swagger UI at `/swagger-ui.html`.
- **Demo mode:** seeds one shared account per staff desk, and a Scenarios
  page that creates real back-office situations (KYC waiting, a stuck
  transfer, a returned payment, a suspicious account, a new hire) through
  PayCore's own flows, so visitors can play each role in turn
  (`POST /api/v1/demo/scenarios/{scenario}`).

## Tech stack

| Area | Technology |
| --- | --- |
| Backend | Java 21, Spring Boot 3.5, Spring Security (OAuth2 resource server / JWT), Spring Data JPA |
| Database | PostgreSQL, Flyway migrations, UUIDv7 primary keys |
| Frontend | React 19, TypeScript, Vite |
| Testing | JUnit 5, Spring Security Test, Testcontainers |

## Project structure

```
src/main/java/com/fintechplatform/paycore/
├── customer/        registration, profiles, account status
├── identity/        login, sessions, access and refresh tokens
├── authorization/   roles, permissions, role assignment audit
├── kyc/             BVN/NIN checks, documents, review workflow
├── account/         customer accounts, account numbers, status audit
├── ledger/          double-entry ledger, balances, statements
├── banktransfer/    bank rail, name enquiry, transfers in and out
├── funding/         card top-ups through a payment provider
├── payment/         payment state machine, status checks, recovery
├── bills/           airtime, data, electricity and TV through a bills aggregator
├── pin/             transaction PIN
├── device/          trusted devices and new-device confirmation
├── limits/          spending and balance limits by KYC tier
├── reconciliation/  provider settlement reports vs PayCore's books
├── operations/      maker-checker corrections
├── security/        JWT and security configuration
├── demo/            Developer Preview demo mode
└── common/          shared config, persistence and networking helpers
src/main/resources/db/migration/   Flyway SQL migrations
paycore-ui/                        React Developer Preview UI
docs/                              design notes (PDF), including PayCore-Code-Notes.pdf:
                                   what each class, method and setting is for
```

## Getting started

### Prerequisites

- Java 21
- PostgreSQL with a database named `paycore`
- Docker, for running the tests (Testcontainers)
- Node.js, only if you want to run the UI

### Configuration

Secrets are read from environment variables and are never stored in the
repository. For local development, copy the example file and fill in your values:

```bash
cp paycore-local.properties.example paycore-local.properties
```

`paycore-local.properties` is git-ignored. The main settings are:

| Variable | Required | Description |
| --- | --- | --- |
| `PAYCORE_DB_PASSWORD` | Yes | Password for the PostgreSQL user |
| `PAYCORE_DB_URL` | No | Defaults to `jdbc:postgresql://localhost:5432/paycore` |
| `PAYCORE_DB_USERNAME` | No | Defaults to `postgres` |
| `PAYCORE_JWT_SECRET` | In production | JWT signing key, at least 32 bytes (`openssl rand -base64 48`) |
| `PAYCORE_KYC_PROVIDER` | No | `dojah` (default) or `simulated` |
| `DOJAH_APP_ID`, `DOJAH_SECRET_KEY` | For Dojah | Sandbox credentials from the Dojah dashboard |
| `PAYCORE_DB_MIGRATION_USERNAME`, `PAYCORE_DB_MIGRATION_PASSWORD` | In production | The table owner Flyway migrates as; defaults to the API's own user |
| `PAYCORE_DB_REQUIRE_LEAST_PRIVILEGE` | In production | `true` refuses to start if the API's database user can delete ledger rows, owns tables or is a superuser |
| `PAYCORE_FUNDING_PROVIDER`, `PAYCORE_RAILS_PROVIDER` | No | `simulated` turns on the card processor and bank rail simulators (demo only) |
| `PAYCORE_RAILS_WEBHOOK_SECRET` | For inbound transfers | Signs inbound transfer webhooks (`openssl rand -base64 48`); blank turns off money in from other banks, including Test Bank |
| `PAYCORE_FUNDING_MAX_TOTAL_PER_ACCOUNT` | No | Most a customer can top up by card in total; defaults to `500000` |
| `PAYCORE_TIER_REVIEW_WORKING_DAYS`, `PAYCORE_TIER_AUTO_APPROVE` | No | Tier 3 review period (default `3` working days) and whether clean requests approve themselves at the end of it (default `true`) |
| `PAYCORE_BILLS_PROVIDER` | No | `simulated` (demo) or `vtpass`; bill payments are off without it |
| `VTPASS_API_KEY`, `VTPASS_PUBLIC_KEY`, `VTPASS_SECRET_KEY` | For VTpass | Sandbox keys from https://sandbox.vtpass.com (Settings > API keys) |
| `PAYCORE_DEMO_ENABLED`, `PAYCORE_DEMO_ADMIN_PASSWORD` | No | Seeds one shared account per staff desk; the password is shown to every visitor of the demo UI |
| `PAYCORE_CORS_ALLOWED_ORIGINS` | No | Only when the UI runs on a different origin than the API, e.g. `http://localhost:5173` |
| `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT` | No | SMTP for verification emails; without it they are written to the log. Locally: Mailpit on `localhost:1025` |
| `PAYCORE_PUBLIC_URL` | No | Where the web app runs; email links point there (`http://localhost:5173`) |
| `PAYCORE_GOOGLE_CLIENT_ID` | No | Sign in with Google: a Web application OAuth client id with the UI as an authorised origin |

### Run the API

```bash
./mvnw spring-boot:run
```

The API starts on `http://localhost:8080`. Flyway creates the tables on first run.

To try it without a Dojah account, use the simulated KYC provider:

```bash
PAYCORE_KYC_PROVIDER=simulated ./mvnw spring-boot:run
```

### Run the UI

```bash
cd paycore-ui
npm install
npm run dev
```

The UI opens on `http://localhost:5173`. See [paycore-ui/README.md](paycore-ui/README.md)
for the guided walkthrough and demo mode.

### Run the tests

```bash
./mvnw test
```

The integration tests start PostgreSQL in Docker, so Docker must be running.

## API overview

All endpoints are under `/api/v1`.

| Area | Endpoints |
| --- | --- |
| Auth | `POST /auth/login`, `/auth/refresh`, `/auth/logout`, `/auth/logout-all` |
| Customers | `POST /customers`, `GET /customers/me`, `GET`/`PATCH`/`DELETE /customers/{id}`, `POST /customers/{id}/suspend`, `/reactivate` |
| KYC (customer) | `GET /kyc`, `GET /kyc/status`, `POST /kyc/start`, `/kyc/bvn`, `/kyc/nin`, `/kyc/documents`, `/kyc/submit` |
| KYC (review) | `GET /kyc/reviews`, `POST /kyc/{id}/start-review`, `/approve`, `/reject`, `/request-information`, BVN/NIN attempt history |
| Accounts (customer) | `POST /accounts`, `GET /accounts`, `GET /accounts/{id}` |
| KYC (Compliance) | `GET /kyc/reviews`, `GET /kyc/{id}/documents`, `GET /kyc/documents/{id}/content`, `POST /kyc/{id}/approve`, `/reject`, `/request-information`, `GET /kyc/tier-upgrades` |
| Accounts (staff) | `GET /admin/customers/{id}/accounts`, `GET /admin/accounts/{id}`, `GET /admin/accounts/{id}/history`, `POST /admin/accounts/{id}/activate`, `/freeze`, `/unfreeze`, `/close` |
| Admin | `GET /admin/customers`, `GET`/`POST /admin/customers/{id}/roles`, `POST .../roles/{role}/revoke`, `GET .../roles/history` |
| Payments (customer) | `GET /payments/{id}`, `GET /payments/reference/{reference}` |
| Payments (staff) | `GET /admin/payments?status=`, `GET /admin/payments/{id}`, `GET /admin/payments/{id}/events`, `POST /admin/payments/{id}/status-check`, `/escalate` |
| Operations | `POST /ops/requests/adjustments`, `/reversals`, `/payment-resolutions`, `GET /ops/requests`, `POST /ops/requests/{id}/approve`, `/reject` |
| Reconciliation | `POST /ops/reconciliation/runs`, `GET /ops/reconciliation/runs`, `GET /ops/reconciliation/runs/{id}`, `POST /ops/reconciliation/items/{id}/redeliver`, `/resolve` |

## Production

Run with `SPRING_PROFILES_ACTIVE=prod`. In this profile the app refuses to start
without a real `PAYCORE_JWT_SECRET`. It also trusts forwarded client IP headers
only from known proxies. See `src/main/resources/application-prod.properties`
for details.

Never enable demo mode (`PAYCORE_DEMO_ENABLED`) on a database with real customers.

## Trying payment failures with the simulators

With `PAYCORE_RAILS_PROVIDER=simulated` and `PAYCORE_FUNDING_PROVIDER=simulated`,
the kobo of the amount decide what the simulated provider does, for transfers
to Test Bank and for card top-ups alike:

| Amount ends in | What happens | What PayCore does |
| --- | --- | --- |
| `.99` | Rejected / declined | Transfer reversed at once; top-up refused |
| `.98` | Paid / charged, but the answer is lost | Shows as pending; the status check (every 30 s) confirms it |
| `.97` | Never reaches the provider | Shows as pending; the status check reverses the transfer or closes the top-up |
| `.96` | Paid / charged, and the provider can never confirm it | Stays unknown; an officer escalates it (Payments) and two officers resolve it (Approvals) |
| `.95` | Test Bank (inbound only): sent, but the notification to PayCore is lost | Nothing credited; reconciliation finds it (missing locally) and the rail delivers it again |
| `.93` | Transfer accepted, then returned by the beneficiary bank | Reconciliation finds the customer still debited (status mismatch); reverse it under maker-checker |
| `.94` | Card charged N1.00 more than asked | Reconciliation finds an amount mismatch |
| `.92` | Card charge never settled by the processor | Reconciliation finds it missing at the provider |

The simulators keep their own records in the `simulator` schema, standing in
for the providers' systems, so a restart changes nothing.
