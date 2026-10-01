# PayCore UI · Developer Preview

A banking-app front end over the PayCore API, so people can **use what the
backend does** and, in the built-in API inspector, watch every request, its auth
header, status and body. Customers get a banking app (home, transfer, add money,
transactions, profile); staff get a separate back office.

The guided walkthrough (the book icon in the header):

1. **Open an account:** register → sign in → verify identity (BVN or NIN,
   sample document, submit) → open a naira account (pending).
2. **Compliance (admin):** the shared demo admin approves the KYC and activates
   the account. Admins cannot move money.
3. **Bank with it:** back as the customer, receive money from **Test Bank** (a
   simulated other bank, sending in your own name from your own Test Bank
   account), send to a PayCore account, and send out to another bank. An amount
   ending in `.99` is rejected by Test Bank and reversed automatically.
4. **Fix a mistake (maker-checker):** an operations officer requests a reversal
   or an adjustment; a different officer approves it. Nothing moves until then.

## Run locally

```bash
# 1. API with demo mode and the simulators on, from the repo root
PAYCORE_DEMO_ENABLED=true PAYCORE_DEMO_ADMIN_PASSWORD='choose-one' \
PAYCORE_KYC_PROVIDER=simulated PAYCORE_FUNDING_PROVIDER=simulated PAYCORE_RAILS_PROVIDER=simulated \
./mvnw spring-boot:run

# 2. UI
cd paycore-ui
npm install
npm run dev            # http://localhost:5173, proxies /api to :8080
```

If the API runs on another port: `PAYCORE_API_URL=http://localhost:8081 npm run dev`.

## Demo mode (backend)

| Variable | Purpose |
| --- | --- |
| `PAYCORE_DEMO_ENABLED=true` | Seeds `admin@paycore.demo` (roles CUSTOMER + ADMIN) and two operations officers, `ops.officer@paycore.demo` and `ops.supervisor@paycore.demo` (role OPERATIONS), on every startup and repairs them if they were changed. Exposes `GET /api/v1/demo`. |
| `PAYCORE_DEMO_ADMIN_PASSWORD` | Required when demo mode is on (min 8 chars). The password of all three staff accounts. **Shown publicly** in the UI. |
| `PAYCORE_DEMO_ADMIN_EMAIL` | Optional, defaults to `admin@paycore.demo`. |
| `PAYCORE_CORS_ALLOWED_ORIGINS` | Comma-separated UI origins when the UI is on a different domain, e.g. `https://paycore-ui.example.com`. |

Because every visitor shares the staff accounts, the API refuses to suspend,
close, edit or change the roles of any of them, and refuses their logout-all
(`403 DEMO_ACCOUNT_PROTECTED`). Staff actions on other customers work normally,
so visitors can act on other visitors' demo accounts. That is acceptable for
sandbox data only: **never enable demo mode on a database with real customers.**

### Who can do what

| Role | Can | Cannot |
| --- | --- | --- |
| CUSTOMER | Open an account, receive money, transfer inside PayCore and to other banks, top up by card | Anything on someone else's account |
| ADMIN | Review KYC, activate / freeze / close accounts, manage roles | Move money in any way |
| OPERATIONS | Request reversals and adjustments, approve other officers' requests | Approve their own request, touch their own account |
| SUPPORT | Look up customers and accounts | Change anything |

### Identity provider (BVN / NIN)

`PAYCORE_KYC_PROVIDER` picks who answers identity checks:

| Value | Behaviour |
| --- | --- |
| `simulated` | Recommended for the public demo. No vendor account needed and nothing to break. Knows one test person: BVN `22222222222` or NIN `70123456789`, John Doe, born 1990-01-01. Any other number or detail fails with a reason, so retry limits still work. The UI labels results as simulated. |
| `dojah` (default) | Real Dojah API (sandbox by default). Needs `DOJAH_APP_ID` and `DOJAH_SECRET_KEY`. NIN is not implemented for Dojah yet and returns `502`. |

A customer needs a passed BVN **or** NIN check plus one document to submit KYC.

### Bank rail (transfers to and from other banks)

`PAYCORE_RAILS_PROVIDER` decides whether money can move between PayCore and
other banks:

| Value | Behaviour |
| --- | --- |
| `none` (default) | Only transfers inside PayCore. |
| `simulated` | A pretend interbank rail connected to **Test Bank**. Every customer has a Test Bank account in their own name and can send themselves money from it (`/api/v1/simulator/test-bank/*`). Transfers out to Test Bank go through name enquiry; any amount ending in `.99` is rejected by the beneficiary bank and PayCore reverses the debit automatically. **No real money moves.** |

Real rails report incoming money to `POST /api/v1/webhooks/bank-rail/inbound`,
signed with `PAYCORE_RAILS_WEBHOOK_SECRET` (HMAC-SHA256 of the body, hex, in
`X-PayCore-Signature`). Each session id is credited at most once.
`PAYCORE_TESTBANK_LIMIT_PER_ACCOUNT` (default `1000000`) caps what Test Bank
sends to one account, because its money is pretend.

### Card top-ups

`PAYCORE_FUNDING_PROVIDER` is `none` (default) or `simulated` (a pretend card
payment confirmed at once; amounts ending in `.99` are declined). A top-up is
only credited once the processor confirms it.
`PAYCORE_FUNDING_MAX_TOTAL_PER_ACCOUNT` (default `500000`) caps what card
top-ups may add to one account; a reversed top-up frees its share.

## Deploy

```
paycore-ui.<domain>  ──HTTPS──▶  paycore-api.<domain>  ──▶  PostgreSQL
```

- **UI:** `VITE_API_BASE_URL=https://paycore-api.<domain> npm run build`, then
  host `dist/` on any static host (Netlify, Vercel, Cloudflare Pages, S3). Add an
  SPA fallback so every path serves `index.html`.
- **API:** `SPRING_PROFILES_ACTIVE=prod`, plus `PAYCORE_JWT_SECRET`, the DB
  variables, the demo variables above and
  `PAYCORE_CORS_ALLOWED_ORIGINS=https://paycore-ui.<domain>`.
