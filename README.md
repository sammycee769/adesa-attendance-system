# ADESA HQ Attendance Management System

Secure employee attendance system: WebAuthn/passkey registered-device
authentication, office-network restriction for attendance actions, and
role-based access (EMPLOYEE / HR / ADMIN).

**Status: Phases 1–8 complete. Phase 9 (pilot) is ready to start once the
checklist gates are met. Office-network enforcement is implemented and verified
at the code level; the on-site network verification (from the office and from a
genuinely different network) is still outstanding and is an ADESA IT task.**

The deployment checklist — what is verified automatically and what still needs a
manual test on the target infrastructure — is in
[`docs/DEPLOYMENT_CHECKLIST.md`](docs/DEPLOYMENT_CHECKLIST.md).

## Documentation

| Document | Purpose |
|---|---|
| [`docs/DEPLOYMENT_CHECKLIST.md`](docs/DEPLOYMENT_CHECKLIST.md) | Every gate, split into verified-automatically and manual-on-target-infrastructure |
| [`docs/SECURITY_REVIEW.md`](docs/SECURITY_REVIEW.md) | Phase 8 security review: methodology, findings and fixes, residual risks, sign-off |
| [`docs/PILOT_PLAN.md`](docs/PILOT_PLAN.md) | Phase 9 pilot: cohort, timeline, success criteria, go/no-go, rollback |
| [`docs/OPERATIONS_RUNBOOK.md`](docs/OPERATIONS_RUNBOOK.md) | Day-to-day procedures, incidents, escalation, backup/restore |

## Repository layout

```
adesa-attendance/
├── backend/            Spring Boot 3.5.x (Java 21, Maven) REST API
│   └── src/main/resources/db/migration/   Flyway migrations
├── frontend/           React 19 + TypeScript + Vite employee application
├── docs/               Deployment checklist (verified vs. manual items)
├── dev.sh              Development helper: up / down / status / approve-device
├── docker-compose.yml  Local PostgreSQL for development
├── .env.example        Configuration template (placeholders only)
└── .gitignore          Excludes secrets, logs and build output
```

Backend packages: `auth`, `employee`, `device`, `attendance`, `admin`,
`audit` (features) plus `config`, `security`, `common` (infrastructure).

Frontend structure: `api` (HTTP client, API modules, WebAuthn ceremonies),
`auth` (session state, route guard), `components`, `pages`, `utils`.

## Prerequisites

- Java 21 (tested with Oracle JDK 21.0.11)
- Maven 3.9+
- Docker + Docker Compose (for local PostgreSQL; tests use Testcontainers)
- Node.js 20+ and npm 10+ (frontend only; tested with Node 24 / npm 11)

## Quick start (local development)

The fastest way to get the whole stack (PostgreSQL + backend + frontend) up:

```bash
./dev.sh up        # PostgreSQL :5433, backend :8080, frontend :5173
./dev.sh status    # what is running
./dev.sh down      # stop the backend and the frontend
```

`dev.sh` reads the local, gitignored `.env` for the ports, the database URL
and the development credentials. It is a development convenience only — it
never relaxes a production setting, and the office-network restriction stays
**off** locally.

The equivalent manual steps:

```bash
# 1. Start PostgreSQL
docker compose up -d

# 2. Run the backend (Flyway migrations run automatically on startup)
cd backend
mvn spring-boot:run

# 3. Verify
curl http://localhost:8080/actuator/health
```

Expected health response: `{"status":"UP"}`.

API docs (Springdoc): http://localhost:8080/api/v1/swagger-ui.html — the
Springdoc endpoints sit behind the same session authentication as the rest of
the API, so they need a signed-in session.

### Frontend

```bash
cd frontend
npm install
npm run dev     # http://localhost:5173 — proxies /api to http://localhost:8080
```

Local development serves the frontend through Vite, which proxies `/api`.
Production supports an exact configured frontend origin using credentialed CORS
and CSRF-protected requests.

Alternatively the development server can run in Docker:

```bash
docker compose --profile frontend up -d   # PostgreSQL + frontend dev server
```

### Local test accounts (development only)

Created through the normal admin API / bootstrap; the passwords live in the
gitignored `.env` and exist **only** on a developer machine.

| Role | Email | Purpose |
|---|---|---|
| ADMIN | `admin@adesa.test` | approve devices, manage employees, network diagnostics |
| EMPLOYEE | `amina.bello@adesa.test` | the employee journey (passkey, sign-in/out) |
| EMPLOYEE | `chidi.okafor@adesa.test` | a second employee (duplicate/one-record-per-day checks) |
| HR | `grace.eze@adesa.test` | the HR role (attendance reports, corrections, audit trail) |

### The administrator and HR screens

An administrator signs in like anybody else and gets the extra navigation:

| Screen | Route | Who |
|---|---|---|
| Devices — approve, revoke, replace | `/devices` | ADMIN |
| Attendance — daily overview, records, PDF export, corrections | `/attendance` | HR, ADMIN |
| Employees — roster (read-only for HR), account management | `/employees` | HR (read), ADMIN (manage) |
| Audit trail | `/audit` | HR (attendance and device entries), ADMIN (all entries) |

The same actions are available on the command line, which is useful when no
browser session is at hand:

```bash
./dev.sh pending-devices        # list devices awaiting approval
./dev.sh approve-device <id>    # approve one
```

## Running tests

```bash
# Backend
cd backend
mvn test

# Frontend
cd frontend
npm test
```

Backend tests use Testcontainers, which starts a throwaway PostgreSQL
container. Docker must be running. Nothing in the test suite bypasses
authentication or network restrictions. Current backend suite: **189 tests**
(attendance business rules and concurrency, network policy and client-address
resolution, security log, employee management, sessions/CSRF and response
headers, WebAuthn ceremonies with a virtual authenticator, rate limiting,
production-safety guard, the API error contract, real embedded-server cookie
checks).

Frontend suite: **64 tests** (Vitest + Testing Library) covering the correct
button for every attendance state, pending actions that block repeated clicks,
API errors shown to the employee, request timeouts and malformed cookies, the
state refreshed after an action, and protected screens that cannot be reached by
changing the URL.

## Frontend (employee, HR and administrator application)

`frontend/` is the React 19 + TypeScript + Vite single-page application. It has
three areas:

- **Employee** — login, device setup and the attendance dashboard (sign in /
  sign out, history, device status, logout).
- **HR and ADMIN** — the attendance report, the employee roster, the audit
  trail and (for administrators) the device approval screen.
- **Guards** — a role guard keeps an employee out of the HR and administrator
  areas. It only avoids showing a screen that would fail; the backend refuses
  the request anyway.

```bash
cd frontend
npm install
npm run dev        # development server on http://localhost:5173
npm test
npm run typecheck  # TypeScript check without emitting
npm run build      # production build into frontend/dist
```

```
frontend/src/
├── api/          HTTP client (session cookie + CSRF header), API modules, WebAuthn ceremonies
├── auth/         session state, the route guard and the role guard
├── components/   layout, attendance panel, history, device card, banners
├── pages/        login, dashboard, device setup
│   └── admin/    devices, attendance report, employees, audit trail
└── utils/        date/time formatting (Africa/Lagos), error messages
```

Security rules the frontend follows (the backend enforces all of them again):

- The frontend never handles an employee id: every request is made for the
  session the browser holds, and the backend takes the identity from the
  server-side principal.
- Hiding or disabling a button is **not** an authorization mechanism. The
  Sign In / Sign Out buttons only mirror the backend's `canSignIn` /
  `canSignOut`; the backend decides and may still refuse the action.
- Every attendance action is preceded by a fresh WebAuthn check of the
  approved device. The single-use device proof travels with the action and is
  consumed by the backend.
- The office-network restriction is enforced by the backend. When it refuses an
  action, the employee sees the reason ("… approved ADESA office network") and
  a hint to continue from the office connection.
- The browser stores no secrets: no password, no token, no credential. The
  session lives in an `HttpOnly` cookie and every state-changing request
  carries the CSRF token.

## Configuration

All secrets and environment-specific values come from environment variables
(see `.env.example`). Local-development defaults are built in so the app runs
out of the box; **these defaults are not valid for production**.

Key settings:

| Variable | Purpose | Notes |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | Active Spring profile | Set to `prod` on Render |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | PostgreSQL connection | Required in prod; use the selected Supabase JDBC endpoint with TLS |
| `ATTENDANCE_NETWORK_ENFORCEMENT_ENABLED` | Office-network enforcement switch (`attendance.network.enforcement-enabled`) | Off by default; must be `true` in prod |
| `ATTENDANCE_NETWORK_LOCAL_DEVELOPMENT_BYPASS` | Explicit local-development override (`attendance.network.local-development-bypass`) | Off by default. When enforcement is off **and** this is off, attendance FAILS CLOSED (HTTP 403). Refused whenever the `prod` profile is active |
| `ATTENDANCE_ALLOWED_IPV4` | Approved office egress CIDRs, IPv4 only (`attendance.network.allowed-ipv4`) | Required explicitly in prod; use the narrow address confirmed from the Render service after office-path verification |
| `ATTENDANCE_TRUSTED_PROXY_ADDRESSES` | Proxies whose `X-Forwarded-For` may be trusted (`attendance.network.trusted-proxies`) | Leave empty unless the actual Render peer, header behavior, and stable proxy address have been verified |
| `ATTENDANCE_CLIENT_IP_HEADER` | Header carrying the forwarded client address | Default `X-Forwarded-For` |
| `SECURITY_LOG_FILE` | Development-only restricted security log | Production security events go to stderr; no writable log directory is required |
| `ATTENDANCE_TIMEZONE` | Timezone that defines the attendance workday | Default `Africa/Lagos` |
| `WEBAUTHN_RP_ID`, `WEBAUTHN_ALLOWED_ORIGINS` | WebAuthn relying party | Must match deployed domain |
| `WEBAUTHN_USER_VERIFICATION_REQUIRED` | Require authenticator biometric/PIN verification | Must be `true` in prod |
| `APP_PUBLIC_URL` | Frontend origin used in setup/recovery links | Required HTTPS origin within the WebAuthn RP ID |
| `MAIL_PROVIDER`, `MAIL_FROM` | Delivery provider and authorized sender | `zoho-api` for Render free; `smtp` remains supported |
| `ZOHO_DATA_CENTER`, `ZOHO_MAIL_ACCOUNT_ID`, `ZOHO_CLIENT_ID`, `ZOHO_CLIENT_SECRET`, `ZOHO_REFRESH_TOKEN` | Zoho HTTPS API configuration | Required for `zoho-api`; [private setup instructions](docs/ZOHO_MAIL_API_SETUP.md) |
| `MAIL_HOST`, `MAIL_PORT` | SMTP host and port | Required only for SMTP; use the mailbox's data centre |
| `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_SMTP_AUTH` | SMTP authentication | Authentication defaults on; store credentials as Render secrets |
| `MAIL_SMTP_STARTTLS_ENABLE`, `MAIL_SMTP_SSL_ENABLE` | SMTP transport encryption | Exactly one enabled; STARTTLS for port 587 or implicit TLS for port 465 |
| `ADESA_BOOTSTRAP_ADMIN_EMPLOYEE_NUMBER`, `ADESA_BOOTSTRAP_ADMIN_FULL_NAME`, `ADESA_BOOTSTRAP_ADMIN_EMAIL`, `ADESA_BOOTSTRAP_ADMIN_PASSWORD` | One-time first-admin bootstrap | Only before any admin/bootstrap audit exists; unset after creation and rotate at first login |
| `VITE_API_BASE_URL` | Frontend API prefix | Actual backend HTTPS origin for Vercel, without `/api/v1`; never put secrets in `VITE_` variables |
| `PORT` | Render's HTTP port | Supplied by Render; Docker startup binds to it |
| `SESSION_IDLE_TIMEOUT`, `SESSION_ABSOLUTE_TIMEOUT` | Session idle / absolute lifetime | Defaults `30m` / `12h` |
| `LOGIN_MAX_FAILURES`, `LOGIN_WINDOW` | Failed-login lockout window | HTTP 429 when exceeded |
| `WEBAUTHN_CHALLENGE_TTL`, `DEVICE_PROOF_TTL` | Single-use ceremony lifetimes | Defaults `2m` |
| `SESSION_COOKIE_SECURE` | Session cookie `Secure` flag | Forced `true` in the `prod` profile |

### Office-network policy (production values require hosted verification)

The application does not assume that an address previously observed at ADESA
is the client address Render will deliver to this container. The office egress
address, Render proxy path, and forwarding-header behavior must be verified
against the deployed service by ADESA IT before configuring the production
allowlist. The `prod` profile requires an explicit allowlist and refuses
startup if enforcement is disabled; it has no production default CIDR.

**Configuration** (environment, never hardcoded):

```properties
attendance.network.enforcement-enabled=${ATTENDANCE_NETWORK_ENFORCEMENT_ENABLED:false}
attendance.network.local-development-bypass=${ATTENDANCE_NETWORK_LOCAL_DEVELOPMENT_BYPASS:false}
attendance.network.allowed-ipv4=${ATTENDANCE_ALLOWED_IPV4:}
attendance.network.trusted-proxies=${ATTENDANCE_TRUSTED_PROXY_ADDRESSES:}
```

- `OfficeNetworkPolicy` parses `attendance.network.allowed-ipv4` at startup and
  **fails startup** if any entry is invalid, if an entry is not IPv4, or if
  enforcement is enabled with no allowlist. Misconfiguration cannot silently
  weaken the policy.
- `ClientIpResolver` derives the client address and **only trusts
  `X-Forwarded-For` when the direct peer is in
  `attendance.network.trusted-proxies`**. From a forwarded chain it takes the
  right-most non-trusted hop, so a client cannot spoof an approved address.
  IPv4-mapped IPv6 peers are normalised to IPv4.
- `AttendanceNetworkGuard` is the single entry point attendance actions call; it
  fails closed (HTTP 403) when the client is outside the approved networks, when
  the client is an IPv6 client, or when the address cannot be determined
  safely.
- Spring's `forward-headers-strategy` is fixed to `NONE` in every profile, so
  the application (not the framework) decides which forwarded headers to trust.

**IPv4 only — IPv6 cannot bypass the policy.** The allowlist is IPv4-only. A
native IPv6 client address is never approved (reason
`IPV6_CLIENT_NOT_APPROVED`), and configuring an IPv6 range is a startup error.
An IPv4-mapped address (`::ffff:a.b.c.d`) is normalised to its IPv4 host.
Native IPv6 clients remain unapproved. Configure only the narrow IPv4
office-egress CIDR verified as seen by the application. Do not allow private,
laptop, link-local, broad provider, or guessed proxy ranges.

The office-network check is an **additional, independent** requirement. It never
replaces the WebAuthn/passkey ceremony or the registered-device check: an
employee inside the office still has to authenticate with an approved device,
and an employee with an approved device is still refused outside the office
network.

**If a load balancer sits in front of the application**, the forwarded office
address is ignored until the proxy is configured in
`attendance.network.trusted-proxies` — the request is then refused (HTTP 403),
because the resolved client is the load balancer. That is fail-closed by
design; it is also why the topology has to be identified before enforcement is
switched on.

While enforcement is disabled the guard **fails closed (HTTP 403)**: with no
explicit local-development bypass, an unconfigured policy never grants access.
This is deliberate — a deployment that forgets to enable enforcement (the exact
condition that produced the reported bypass) can no longer record attendance
from an unverified network. Only the explicit
`ATTENDANCE_NETWORK_LOCAL_DEVELOPMENT_BYPASS=true` turns the check off, and that
flag is refused under the `prod` profile (see the safety guard below), so it can
never weaken production. Local development sets the bypass in `.env`/`dev.sh`.

**Testing the restriction on a developer machine.** The allowlist only accepts
IPv4, and `localhost` usually resolves to `::1` (IPv6) first. Use the IPv4
literal so the local request looks like an IPv4 client:

```bash
# approved address (add it to ATTENDANCE_ALLOWED_IPV4 for the test)
curl http://127.0.0.1:8080/...            # 127.0.0.1/32

# IPv6 client - always refused
curl http://[::1]:8080/...                # reason IPV6_CLIENT_NOT_APPROVED
```

### Denied attempts and the security log

Every refused attendance attempt is written to the dedicated `SECURITY` logger.
In production it is routed to stderr, separately from application logs on
stdout, for Render capture. In non-production it is written to a restricted
rolling file (configurable with `SECURITY_LOG_FILE`). Each entry carries the
timestamp, resolved client IP, reason and acting employee:

```
2026-10-21T10:13:44.076+01:00 WARN SECURITY - event=attendance_denied action=SIGN_IN \
    reason=OUTSIDE_APPROVED_NETWORK client_ip=198.51.100.77 employee=e7584eb0-…
```

The security logger does not propagate to the general application log or
business audit trail. Restrict access to captured security logs to authorized
operations/IT staff; the business audit trail keeps the generic reason
`NETWORK_NOT_APPROVED` and no network detail.

### Verifying the office address before enforcement is switched on

Enforcement is switched on only after the application has been observed to
resolve the office public address for a request that really came through the
office connection. The check uses an ADMIN-only diagnostics endpoint that
reports the client address the application sees for the caller's own request:

| Method & path | Purpose |
|---|---|
| `GET /api/v1/admin/network/client-address` | The resolved client address, whether it is inside an approved office network, whether it is IPv4, and the policy state (ADMIN only) |

The resolved address is the **authoritative** value — a user-supplied address is
never accepted as the source of truth. The response never contains the
configured networks or the trusted proxies. The raw forwarding header is
reported separately as `untrustedForwardedClientAddress` for troubleshooting
only; it never influences the policy decision.

Safe verification order:

1. Use a non-production Render service with a separate scratch database and
   the same service topology intended for production. Set
   `SPRING_PROFILES_ACTIVE=prod`,
   `ATTENDANCE_NETWORK_ENFORCEMENT_ENABLED=true`,
   `ATTENDANCE_NETWORK_LOCAL_DEVELOPMENT_BYPASS=false`, and a reserved
   documentation-only CIDR such as `192.0.2.1/32` as a temporary deny-all
   canary allowlist. This is not the office address or a production
   configuration. Do not use production data.
2. Keep existing ADMIN authentication enabled. Restrict access at the
   Render/edge layer to the test operators where possible. From ADESA HQ Wi-Fi,
   sign in as a test ADMIN and call `GET
   /api/v1/admin/network/client-address`; repeat from a mobile hotspot. Do not
   expose the endpoint unauthenticated or weaken the security matchers.
3. Record `resolvedClientAddress` from both paths and compare the separately
   labelled `untrustedForwardedClientAddress` only as evidence. A forwarded
   value is never trusted just because it appears in that field. Confirm with
   Render's current service/network documentation and logs whether the
   container peer is the client or a proxy. Only configure a proxy if its
   stable direct-peer IPv4 address and exact header behavior are verified; the
   resolver accepts only explicit `/32` proxy addresses. Stop if Render uses
   dynamic proxy addresses or the chain cannot be verified safely.
4. Obtain the actual narrow office IPv4 egress address from ADESA IT/router
   records and confirm the application resolves that address from office
   Wi-Fi. Confirm the hotspot resolves to a different, unapproved IPv4 address.
   If either path is ambiguous, keep the canary in place and attendance
   unavailable.
5. Only after steps 3 and 4 succeed, set
   `ATTENDANCE_ALLOWED_IPV4=<verified-office-ipv4>/32`, retaining enforcement
   `true` and the bypass `false`. Deploy and repeat authenticated diagnostics
   from office and hotspot. Confirm office is within the allowlist and hotspot
   outside it before any real attendance use. Keep ADMIN-only authorization and
   edge access controls in place.

The hosted network procedure has not been executed. Automated tests verify the
resolver and fail-closed policy, not Render's real network path.

### Render deployment configuration

Use the repository's `backend` directory as the Docker build context/root
directory because `backend/Dockerfile` copies `pom.xml` and `src` relative to
that directory. The container listens on Render's supplied `PORT` (with an
8080 fallback). The health-check path is `/actuator/health`; do not use the
network diagnostic path as a health check.

The Dockerfile builds the executable Spring Boot jar but skips tests. Run the
repository CI workflow or Maven and frontend checks before deployment. Flyway
migrations run during application startup and Hibernate uses
`ddl-auto: validate`, so migration or schema mismatch fails startup. No
production seed data is configured.

This repository has no Render or Vercel service manifest. The GitHub Actions
workflow runs checks on pushes and pull requests, but it does not by itself
prevent a Render auto-deploy. Keep auto-deploy disabled or configure Render to
deploy only after the required CI checks pass.

For Supabase, set `DB_URL` to the JDBC form of the actual project's direct or
pooler endpoint, and set `DB_USERNAME`/`DB_PASSWORD` to provisioned database
credentials. Production forces PostgreSQL `sslmode=require`. Use a
migration-capable least-privilege role. The project host, port, username
(including any pooler suffix), database, and supported pool mode must be copied
from the Supabase dashboard and tested against a scratch database; none can be
verified from this repository. Prefer direct or session-mode connections unless
the chosen transaction pooler is proven compatible with Flyway, Hibernate, and
prepared statements.

For Vercel, set `VITE_API_BASE_URL` to the actual backend HTTPS origin without
`/api/v1`. The backend allows credentialed CORS only from `APP_PUBLIC_URL`.
The CSRF bootstrap returns JSON plus a backend cookie; the frontend echoes the
JSON token as a header. Production cookies are Secure and default to SameSite=None.
Browsers may block cookies across unrelated `vercel.app` and `onrender.com` sites;
prefer sibling custom domains and set `SESSION_COOKIE_SAME_SITE=lax` in that case.
See [the deployment handover](docs/PRODUCTION_HANDOVER.md) for rollout gates.

### Cloud firewall / edge restrictions (required second layer)

The backend policy is the **second** layer. ADESA IT must additionally restrict
the attendance endpoints at the cloud edge (security group, load balancer
access control list, WAF rule), so that unapproved routes cannot reach the
application at all. The items are listed in `docs/DEPLOYMENT_CHECKLIST.md`,
section 3.

### Production safety guard

With the `prod` profile active (`SPRING_PROFILES_ACTIVE=prod`), the
application **refuses to start** unless the attendance network restriction is
enabled and at least one approved CIDR is configured
(`ProductionSafetyValidator`). An accidental network bypass in production is
therefore a startup failure, not a silent misconfiguration.

The `prod` profile also **refuses to start** if
`ATTENDANCE_NETWORK_LOCAL_DEVELOPMENT_BYPASS=true` is set: the bypass exists only
for developer machines and would otherwise disable the office-network
restriction in production.

The `prod` profile also **never enables the restriction implicitly**: the
default is `false`, so an operator has to switch it on deliberately — after the
client address has been verified. The startup error message states exactly
that.

Beyond the network settings, the `prod` profile refuses to start unless the
rest of the production configuration is safe as well:

- `DB_PASSWORD` is set and is **not** the development default (there is no
  fallback value in production);
- `server.servlet.session.cookie.secure` is `true`;
- `WEBAUTHN_RP_ID` is the deployed domain (not `localhost`, not an IP) and every
  entry in `WEBAUTHN_ALLOWED_ORIGINS` is an HTTPS origin within that domain;
- `WEBAUTHN_USER_VERIFICATION_REQUIRED` is `true`;
- the allowlist is at most a `/24` (a `/0`, `/8` or `/16` would approve the
  whole internet or a whole provider network) and each trusted proxy is a single
  `/32` host address.

Outside production, the local-development bypass is only accepted when the
`dev` profile is explicitly active (`SPRING_PROFILES_ACTIVE=dev`, which
`dev.sh` sets). A deployment that forgets to activate a profile but leaves the
bypass on therefore refuses to start instead of silently disabling the office
network control.

## Backup and restore (development PostgreSQL container)

```bash
# Backup
docker compose exec postgres pg_dump -U adesa_app adesa_attendance > backup.sql

# Restore
docker compose exec -T postgres psql -U adesa_app adesa_attendance < backup.sql
```

Production backup/restore procedures (schedule, offsite copy, and a verified
restore test) must be defined with ADESA IT before deployment.

## Creating the first administrator

There is no public self-registration endpoint — ever. The very first ADMIN
account is created by a controlled bootstrap at application startup:

```bash
ADESA_BOOTSTRAP_ADMIN_EMPLOYEE_NUMBER=ADMIN-001 \
ADESA_BOOTSTRAP_ADMIN_FULL_NAME='Site Administrator' \
ADESA_BOOTSTRAP_ADMIN_EMAIL=admin@adesa.example \
ADESA_BOOTSTRAP_ADMIN_PASSWORD='<a strong password, min 12 chars>' \
mvn spring-boot:run
```

Rules enforced by the backend:

- Bootstrap runs only when no ADMIN (active or inactive) and no
  `FIRST_ADMIN_BOOTSTRAPPED` audit event exist. The check and creation use a
  single database transaction and an exclusive employee-table lock, preventing
  concurrent startup races even with different bootstrap emails. Deactivation
  or demotion never permits bootstrap to run again.
- All four values must be provided; the password must be 12-128 characters and
  comply with the configured password policy. It is stored only as a BCrypt
  hash and the account is forced to change it before using other features.
- The creation is written to `audit_logs` (`FIRST_ADMIN_BOOTSTRAPPED`).
- **Unset the variables after the account exists.** Do not persist the
  bootstrap password anywhere; the administrator must change it right after
  first sign-in (a password change bumps the credentials version and signs
  every session out).

## Phase 2 API (implemented)

All under `/api/v1/admin/**`, **ADMIN role only** (enforced server-side):

| Method & path | Purpose |
|---|---|
| `POST /api/v1/admin/employees` | Create employee (409 on duplicate number/email, 400 on validation errors) |
| `GET /api/v1/admin/employees` | List with optional `role`, `active`, `search` filters |
| `GET /api/v1/admin/employees/{id}` | Get one (404 if unknown) |
| `PATCH /api/v1/admin/employees/{id}` | Partial update: name, email, role, activate/deactivate, password reset |
| `POST /api/v1/admin/employees/{id}/reactivate` | Reactivate a deactivated account (including a deactivated administrator) |

Every change writes audit entries (`EMPLOYEE_CREATED/UPDATED/ACTIVATED/
DEACTIVATED/REACTIVATED/ROLE_CHANGED/PASSWORD_RESET`) including actor, target and
changed fields — never passwords. Error responses use a consistent `ApiError`
body (timestamp, status, error, message, path, fieldErrors).

**Administrator-account rules** (enforced in the service layer, not just the UI):

- An administrator **cannot be deactivated** through `PATCH
  /api/v1/admin/employees/{id}`. The attempt is refused with HTTP 409 and the
  refusal is audited (`EMPLOYEE_ACTIVATION_REJECTED`, reason
  `ADMIN_DEACTIVATION_NOT_ALLOWED`).
- A previously deactivated administrator **can be reactivated** by an authorized
  administrator through `POST /api/v1/admin/employees/{id}/reactivate`
  (ADMIN only). The action only flips the active flag — attendance history is
  never touched — and it is audited (`EMPLOYEE_REACTIVATED`, reason
  `ADMIN_ACCOUNT_REACTIVATION`).
- **The system is never left without an active administrator.** Changing the
  role of the last active administrator away from `ADMIN` is refused (HTTP 409,
  audited). Reactivating an already-active account is a conflict (HTTP 409).
- **Recovering an inactive administrator** requires an authorized existing
  administrator or a separately reviewed operator recovery procedure. Restart
  bootstrap never creates or reactivates accounts as a recovery shortcut.

## Phase 3 API (implemented)

### Sessions (server-managed; no JWT, nothing in localStorage)

| Method & path | Purpose |
|---|---|
| `POST /api/v1/auth/login` | Password sign-in → `JSESSIONID` session (401 bad credentials, 429 rate limited) |
| `POST /api/v1/auth/logout` | Invalidate the server-side session (204) |
| `GET /api/v1/auth/me` | Current identity + device status (401 when signed out/expired) |
| `GET /api/v1/auth/csrf` | Issue the readable `XSRF-TOKEN` cookie for the SPA (204) |

- The session cookie is `HttpOnly`, `SameSite=Lax`, `Path=/` and `Secure` in the
  `prod` profile. The CSRF cookie is deliberately readable by the SPA and must be
  echoed in the `X-XSRF-TOKEN` header on every state-changing request.
- Sign-in rotates the session id (fixation protection). An idle timeout
  (`SESSION_IDLE_TIMEOUT`) and an absolute lifetime (`SESSION_ABSOLUTE_TIMEOUT`)
  are both enforced; the latter needs a filter because Spring only implements
  idle timeouts.
- Failed passwords are limited per email (`LOGIN_MAX_FAILURES` inside
  `LOGIN_WINDOW` → HTTP 429 with `Retry-After`). The response never reveals
  whether the email exists.
- A password change, a role change, deactivation or a device revocation bumps
  the employee's credentials version, which invalidates every existing session
  on the next request.

### WebAuthn / registered device

| Method & path | Purpose |
|---|---|
| `POST /api/v1/auth/webauthn/register/options` | Registration options (409 if a device is already approved) |
| `POST /api/v1/auth/webauthn/register` | Verify the attestation → device becomes `PENDING_APPROVAL` (201) |
| `POST /api/v1/auth/webauthn/authenticate/options` | Assertion options (409 if no approved device) |
| `POST /api/v1/auth/webauthn/authenticate` | Verify the assertion → single-use, session-bound device proof |
| `GET /api/v1/devices/me` | My device status (`NONE`, `PENDING_APPROVAL`, `APPROVED`, `REVOKED`, `REPLACED`) |

- Challenges are single-use and expire after `WEBAUTHN_CHALLENGE_TTL`; a
  device proof expires after `DEVICE_PROOF_TTL` and is bound to the session
  that requested it (only the session id's SHA-256 hash is stored), so a
  captured proof cannot be replayed from another session.
- Registration alone does **not** enable attendance: the device must be
  approved by an administrator first.

### Device administration (ADMIN only)

| Method & path | Purpose |
|---|---|
| `GET /api/v1/admin/devices?status=PENDING_APPROVAL` | List devices by status |
| `POST /api/v1/admin/devices/{id}/approve` | Approve a pending device (409 if it is not pending) |
| `POST /api/v1/admin/devices/{id}/revoke` | Revoke a device (invalidates the employee's sessions) |
| `POST /api/v1/admin/devices/{id}/replace` | Mark a device `REPLACED` so a replacement can be registered |

Approval is the trust anchor for attendance: only an approved device can
produce the device proof that attendance actions require.

## Phase 5 API (implemented) — the attendance engine

All under `/api/v1/attendance`, for the signed-in employee. The identity always
comes from the session: the endpoints accept no employee id, so an employee can
only ever record or read their own attendance.

| Method & path | Purpose |
|---|---|
| `POST /api/v1/attendance/sign-in` | Sign in for the current workday (201) |
| `POST /api/v1/attendance/sign-out` | Sign out for the current workday (200) |
| `GET /api/v1/attendance/me/today` | State, timestamps and which action the backend currently accepts |
| `GET /api/v1/attendance/me/history` | Own history, newest workday first (`from`, `to`, `page`, `size`) |

Both actions take `{"deviceProofId": "…"}` — the single-use, session-bound proof
issued by `POST /api/v1/auth/webauthn/authenticate`.

### What a sign-in / sign-out has to satisfy

Every attendance action is checked against the same chain, in this order:

1. **Office network** — the request must come from an approved network, else
   HTTP 403 (`AttendanceAccessGuard` → `AttendanceNetworkGuard`).
2. **Active account** — a deactivated account is refused with HTTP 403.
3. **Approved device** — the employee must have an `APPROVED` registered device,
   and the request must carry a live device proof that belongs to that device
   and to the current session. A missing, expired, replayed or foreign proof is
   refused with HTTP 403.
4. **Business rules** — one record per workday. A duplicate sign-in or a
   sign-out without a sign-in is refused with HTTP 409.

The device proof is consumed inside the same transaction as the attendance
change, so a failed action does not burn the proof, and a proof can never be
used twice.

### Concurrency and data integrity

- `attendance_records` has a unique constraint on `(employee_id,
  attendance_date)`. Two simultaneous sign-ins cannot create two records: the
  database decides the winner, the loser gets HTTP 409.
- The sign-out takes a row-level write lock, so a workday can never be closed
  twice, whatever order concurrent requests arrive in.
- Timestamps are generated by the server; the employee's device clock is never
  trusted. The workday is the employee's local calendar day in
  `ATTENDANCE_TIMEZONE` (`Africa/Lagos` by default).
- Sign-in and sign-out are audited (`ATTENDANCE_SIGNED_IN`,
  `ATTENDANCE_SIGNED_OUT`); refused actions are audited as
  `ATTENDANCE_ACTION_REJECTED` with the reason (network, device proof,
  sequence).

### Attendance status

`GET /api/v1/attendance/me/today` returns `NOT_SIGNED_IN`, `SIGNED_IN` or
`SIGNED_OUT` plus `canSignIn` / `canSignOut`. `NOT_SIGNED_IN` only means "no
record yet" — it never means "absent"; that is an HR reporting decision, not an
attendance state.

## Phase 7 API (implemented) — the HR and administrator interfaces

### Role separation

| Role | May do | May **not** do |
|---|---|---|
| EMPLOYEE | sign in/out, own history, own device | read any report, see the roster, see the audit trail |
| HR | read the roster, read and export attendance reports, correct attendance records, read the attendance and device audit entries | create or change accounts, change roles, approve or revoke devices, read the network diagnostics, see the account-administration audit entries |
| ADMIN | everything HR may do, plus account management, device approval/revocation/replacement, the full audit trail, the network diagnostics | — |

The split is enforced twice: the filter chain opens only the listed paths to
HR, and every method carries its own `@PreAuthorize`. HR never receives
unrestricted administration powers by accident — the master brief gives HR
"authorized" corrections and "relevant" audit records, so the audit trail is
narrowed server-side to the attendance and device actions. The frontend mirrors
the split for convenience; it is not the boundary.

### Attendance reporting

| Method & path | Purpose |
|---|---|
| `GET /api/v1/admin/attendance` | Attendance records, newest workday first. Filters: `employeeId`, `search` (name/number/email), `from`, `to` (workdays), `status` (`SIGNED_IN`/`SIGNED_OUT`), `page`, `size` |
| `GET /api/v1/admin/attendance/overview` | Daily overview: every active employee with their state for a workday (`date`, defaults to the current workday) |
| `GET /api/v1/admin/attendance/export` | The same report as a PDF file (`application/pdf`) |
| `PATCH /api/v1/admin/attendance/{id}` | Correct a record — `{"signInAt", "signOutAt", "reason"}` (HR and ADMIN) |

- A correction needs a **mandatory reason** (at least 10 characters). The
  backend refuses a correction without one, so a record can never be silently
  overwritten.
- A correction is **transactional**: the record change, the entry in
  `attendance_corrections` (previous values, new values, reason, actor) and the
  audit entry (`ATTENDANCE_CORRECTED`) are committed together or not at all.
  The record itself keeps only the current values — the previous ones stay in
  the correction history.
- A sign-out before the sign-in is refused (HTTP 400); an unknown record gives
  HTTP 404.
- The PDF export is refused (HTTP 400) when it would match more than 10 000
  records, instead of silently truncating the report. Values that a spreadsheet
  would evaluate as a formula are neutralised.

### The audit trail

| Method & path | Purpose |
|---|---|
| `GET /api/v1/admin/audit-logs` | Audit entries, newest first. Filters: `action`, `targetType`, `actorId`, `from`, `to`, `page`, `size` |

An administrator sees every action. HR sees the attendance and device actions
only (`ATTENDANCE_*`, `DEVICE_*`); the restriction is applied in the service,
so it cannot be lifted with a query parameter. Requesting an action that the
role may not see returns an empty page instead of revealing that the action
exists.

### Database changes

`V5__create_attendance_corrections_table.sql` adds the `attendance_corrections`
table (the correction history) and the `last_used_at` column on
`registered_devices` (when the credential was last used for an assertion).

## Open points for ADESA IT and HR

Decisions that are deliberately **not** made in the code, because they belong to
ADESA:

| # | Open point | Owner | Current behaviour |
|---|---|---|---|
| 1 | Hosting topology (direct exposure vs. platform load balancer) and trusted proxy addresses | ADESA IT | No production office CIDR is assumed. The trusted-proxy list is empty until Render's actual path, forwarded-header behavior, and stable peer address are verified; enforcement remains a startup gate. |
| 2 | `WEBAUTHN_RP_ID` and the allowed origins of the deployed domain | ADESA IT | `localhost` / `http://localhost:5173` placeholders. Passkeys only work once these match the real domain. |
| 3 | Working hours, late/early rules and how absences are derived | HR | The engine records sign-in/sign-out only. It never marks anyone absent. |
| 4 | A workday that was signed in but never signed out | HR | A sign-out only applies to the current workday; an open workday from an earlier day stays open and needs an authorised correction. |
| 5 | Who may approve device registrations and replacements | ADESA | ADMIN only, at the moment. The master brief lets HR "review device replacement requests **if explicitly authorized by ADESA policy**" — that authorization has not been given, so HR gets no device administration. |
| 6 | Retention period for audit logs and attendance records | ADESA | Nothing is deleted automatically. |
| 7 | Whether strict physical-device binding is required | ADESA IT | Passkeys may be synced; a device-bound authenticator would have to be evaluated. |
| 8 | Session storage when more than one application instance runs | ADESA IT | Sessions live in the instance's memory; a shared store is required for a multi-instance deployment. |
| 9 | How the built frontend is served in production (reverse proxy, SPA fallback, TLS termination) | ADESA IT | `npm run build` produces `frontend/dist`, hosted on Vercel with SPA fallback. The frontend calls the Render HTTPS API origin directly, with exact-origin CORS and Secure cookies. Confirm browser cookie compatibility before onboarding. |
| 10 | The exact scope of the HR attendance view (which employees HR may see, whether corrections need a second approval) | ADESA HR | Every active employee's attendance is visible to HR. A correction takes effect immediately and is recorded with the officer's name; a second-person approval is not implemented. |

## Security notes and limitations

- Attendance actions are restricted to the verified ADESA office egress CIDR by
  a backend policy. No public IP is assumed in production configuration;
  enforcement is a startup gate and must not be enabled for real attendance
  until the Render path is confirmed from office Wi-Fi and a mobile hotspot. A
  cloud firewall or edge-level restriction remains a required second layer.
- The office-network policy is IPv4-only. IPv6 traffic is never approved and
  cannot be used to bypass the IPv4 allowlist.
- The resolved client address of a refused attempt is written to the restricted
  security log only, never to the general application log or to the business
  audit trail.
- WebAuthn passkeys may be synced across devices; a passkey does not prove use
  of one specific physical device. Strict device binding requires a
  device-bound authenticator — to be evaluated with ADESA IT.
- Office-network access does not prove physical presence (VPN routes may
  qualify); ADESA IT defines which routes count.
- HTTPS is mandatory in every deployed environment; no environment contains
  real employee data or production secrets in this repository.
- Sessions are server-side and invalidation is immediate (password change,
  role change, deactivation, device revoke/replace). A deployment with more
  than one application instance therefore needs a shared session store
  (Spring Session) and sticky sessions; the current configuration keeps the
  session in the instance's memory.
- The login rate limiter is in-memory, so it protects a single instance only.
  Multi-instance deployments need a shared limiter (or a WAF rule) in front of
  the application.
- WebAuthn `rp-id` and the allowed origins are placeholders and must be
  changed to the real domain before any deployment, otherwise passkeys will
  not work in a deployed browser.
- The frontend is **not** a security boundary. Every permission, the
  office-network restriction and the device check are enforced by the backend;
  disabling a button in React is not an authorization mechanism.
- The browser never holds credential material: no password, no token and no
  passkey material is stored. The session lives in an `HttpOnly` cookie, the
  CSRF token in the deliberately readable `XSRF-TOKEN` cookie, and every
  state-changing request echoes it in the `X-XSRF-TOKEN` header.
