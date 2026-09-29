# PayCore UI · Developer Preview

A thin React UI over the PayCore API. It is not a product frontend; it exists so
people can **click through what the backend already does** and watch it happen
in the built-in API inspector (every request, its auth header, status and body).

The guided walkthrough:

1. **As a customer:** register → sign in → dashboard → KYC (BVN or NIN check,
   sample document upload, submit) → security tests
   (`200` / `401` / `401` tampered / `403` missing permission / `403` self-approval).
2. **As an admin:** "Do you want to see the flow as an admin?" signs the visitor
   out (revoking the session server-side) and shows the shared demo admin's
   credentials. The admin reviews the visitor's own KYC and manages roles with a
   reason-required audit trail.
3. **Back as the customer:** sign in again and see the reviewer's decision.

## Run locally

```bash
# 1. API with demo mode on (seeds the shared admin), from the repo root
PAYCORE_DEMO_ENABLED=true PAYCORE_DEMO_ADMIN_PASSWORD='choose-one' PAYCORE_KYC_PROVIDER=simulated ./mvnw spring-boot:run

# 2. UI
cd paycore-ui
npm install
npm run dev            # http://localhost:5173, proxies /api to :8080
```

If the API runs on another port: `PAYCORE_API_URL=http://localhost:8081 npm run dev`.

## Demo mode (backend)

| Variable | Purpose |
| --- | --- |
| `PAYCORE_DEMO_ENABLED=true` | Seeds `admin@paycore.demo` (ACTIVE, roles CUSTOMER + ADMIN) on every startup and repairs it if it was changed. Exposes `GET /api/v1/demo`. |
| `PAYCORE_DEMO_ADMIN_PASSWORD` | Required when demo mode is on (min 8 chars). **Shown publicly** in the UI. |
| `PAYCORE_DEMO_ADMIN_EMAIL` | Optional, defaults to `admin@paycore.demo`. |
| `PAYCORE_CORS_ALLOWED_ORIGINS` | Comma-separated UI origins when the UI is on a different domain, e.g. `https://paycore-ui.example.com`. |

Because every visitor shares the admin account, the API refuses to suspend,
close, edit or change the roles of the demo admin, and refuses its
logout-all (`403 DEMO_ACCOUNT_PROTECTED`). Admin actions on other customers work
normally, so a visitor with the admin login can also act on other visitors'
demo accounts. That is acceptable for sandbox data only: **never enable demo
mode on a database with real customers.**

### Identity provider (BVN / NIN)

`PAYCORE_KYC_PROVIDER` picks who answers identity checks:

| Value | Behaviour |
| --- | --- |
| `simulated` | Recommended for the public demo. No vendor account needed and nothing to break. Knows one test person: BVN `22222222222` or NIN `70123456789`, John Doe, born 1990-01-01. Any other number or detail fails with a reason, so retry limits still work. The UI labels results as simulated. |
| `dojah` (default) | Real Dojah API (sandbox by default). Needs `DOJAH_APP_ID` and `DOJAH_SECRET_KEY`. NIN is not implemented for Dojah yet and returns `502`. |

A customer needs a passed BVN **or** NIN check plus one document to submit KYC.

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
