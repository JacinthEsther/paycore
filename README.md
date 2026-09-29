# PayCore

PayCore is the backend for a Nigerian financial platform. It covers the parts every
fintech needs before money can move: customer onboarding, secure sign-in,
role-based access control, KYC (Know Your Customer) identity verification and
customer accounts, the foundation for the ledger and transactions to come.

The repository also contains **paycore-ui**, a small React app that lets you
click through the API and see each request and response as it happens.

## Features

- **Customer onboarding:** registration with validated Nigerian phone numbers,
  profile updates, and suspend, reactivate or close an account.
- **Authentication:** email and password login with short-lived JWT access
  tokens and rotating refresh tokens. Sessions sign out after 15 minutes of
  inactivity and after 12 hours at most, and customers can log out of all devices.
- **Roles and permissions:** `CUSTOMER`, `SUPPORT` and `ADMIN` roles, each with a
  set of permissions checked on every endpoint. Admins can assign and revoke
  roles, and every change is recorded with a reason in an audit history.
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
  - No balance on the account: balances will come from the ledger
- **Demo mode:** seeds a shared admin account so visitors can try the admin
  review flow in the Developer Preview.

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
├── security/        JWT and security configuration
├── demo/            Developer Preview demo mode
└── common/          shared config, persistence and networking helpers
src/main/resources/db/migration/   Flyway SQL migrations
paycore-ui/                        React Developer Preview UI
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
| Accounts (staff) | `GET /admin/customers/{id}/accounts`, `GET /admin/accounts/{id}`, `GET /admin/accounts/{id}/history`, `POST /admin/accounts/{id}/activate`, `/freeze`, `/unfreeze`, `/close` |
| Admin | `GET /admin/customers`, `GET`/`POST /admin/customers/{id}/roles`, `POST .../roles/{role}/revoke`, `GET .../roles/history` |

## Production

Run with `SPRING_PROFILES_ACTIVE=prod`. In this profile the app refuses to start
without a real `PAYCORE_JWT_SECRET`. It also trusts forwarded client IP headers
only from known proxies. See `src/main/resources/application-prod.properties`
for details.

Never enable demo mode (`PAYCORE_DEMO_ENABLED`) on a database with real customers.
