# ADESA production deployment handover

Deploy only within the owner-approved scope. A backend deployment
runs Flyway and therefore changes the production database. No production accounts
or attendance records have been created by this audit. Existing local edits were
present before the audit and must be reviewed with these changes.

## Immediate gates

1. Rotate the previously exposed Supabase database password in Supabase Database
   Settings. Update every authorized consumer privately; never paste the secret
   into chat, a command argument, source control, or Vercel. Confirm rotation
   before any production use. Take/confirm a recoverable database backup first.
2. Obtain the real Render URL, frontend domain, Render plan and Supabase Connect
   parameters. Zoho SMTP requires a paid Render service: free services block
   outbound 25/465/587 (https://render.com/docs/free). The owner selected the
   Zoho HTTPS API to retain the free plan; follow [private OAuth setup](ZOHO_MAIL_API_SETUP.md).
3. Establish a trustworthy client-IP path. Keep enforcement on and development
   bypass off. Startup success does not prove that office IP resolution works.
4. Run full local verification with Docker available before approving a push.

## Confirmed frontend

Production frontend: https://adesa-attendance-frontend.vercel.app
Vercel project: adesa-attendance-frontend (samuel's projects).
This deployment serves the UI; the backend connection is still pending.
Use APP_PUBLIC_URL=https://adesa-attendance-frontend.vercel.app,
WEBAUTHN_RP_ID=adesa-attendance-frontend.vercel.app, and
WEBAUTHN_ALLOWED_ORIGINS=https://adesa-attendance-frontend.vercel.app
unless a final custom domain is selected before onboarding.

## Render configuration (owner applies after approval)

Repository: sammycee769/adesa-attendance-system, branch main. If root directory is
empty, Dockerfile path is backend/Dockerfile and Docker context is backend. Do
not also set the service root to backend with these same relative paths.
Runtime: Docker; the image runs Java 21 as appuser and binds Render's PORT.
Health check: /actuator/health (includes database health; details hidden).
Use one instance initially: sessions, rate limits and challenges are in memory.
Restarts sign sessions out; attendance records remain in PostgreSQL.
The owner confirmed auto-deploy is enabled and authorized pushing deployment
changes; a push can trigger production migrations. No Docker command override is needed.

Known non-secret values:

```dotenv
SPRING_PROFILES_ACTIVE=prod
ATTENDANCE_TIMEZONE=Africa/Lagos
ATTENDANCE_NETWORK_ENFORCEMENT_ENABLED=true
ATTENDANCE_NETWORK_LOCAL_DEVELOPMENT_BYPASS=false
ATTENDANCE_CLIENT_IP_HEADER=X-Forwarded-For
WEBAUTHN_USER_VERIFICATION_REQUIRED=true
MAIL_PROVIDER=zoho-api
```

Provisional, pending live path verification:

```dotenv
ATTENDANCE_ALLOWED_IPV4=105.127.6.174/32
```

Values the owner must obtain (not guesses):

| Variable | Source / requirement |
| --- | --- |
| DB_URL | Supabase Connect: JDBC URL, no embedded credentials; session pooler port 5432 or reachable direct endpoint |
| DB_USERNAME | Exact selected connection username, including project suffix where supplied |
| DB_PASSWORD | Newly rotated database password, entered privately in Render |
| APP_PUBLIC_URL | Exact HTTPS frontend origin, no path/query; also the sole allowed credentialed CORS origin |
| WEBAUTHN_RP_ID | Frontend hostname only, no scheme/path; choose final domain before registering passkeys |
| WEBAUTHN_ALLOWED_ORIGINS | Exact HTTPS frontend origin(s) valid for that RP ID; no wildcard or preview domains |
| ATTENDANCE_TRUSTED_PROXY_ADDRESSES | Verified stable proxy IPv4 hosts only (/32); leave empty until justified |
| ZOHO_DATA_CENTER | Mailbox region: US, EU, IN, AU, JP, CA, CN, AE or SA |
| ZOHO_MAIL_ACCOUNT_ID | Account ID from authenticated Zoho Mail accounts API |
| ZOHO_CLIENT_ID | Self Client OAuth ID, entered privately |
| ZOHO_CLIENT_SECRET | Self Client OAuth secret, entered privately |
| ZOHO_REFRESH_TOKEN | Offline OAuth refresh token, entered privately |
| MAIL_FROM | Authorized mailbox or verified sending alias |
| SESSION_COOKIE_SAME_SITE | none for unrelated hosting sites; lax for HTTPS sibling custom domains |

Optional SMTP on a host that permits it requires MAIL_HOST, MAIL_USERNAME,
MAIL_PASSWORD and MAIL_SMTP_AUTH=true. Implicit TLS alternative: MAIL_PORT=465, MAIL_SMTP_SSL_ENABLE=true,
MAIL_SMTP_STARTTLS_ENABLE=false. Exactly one transport must be enabled. STARTTLS
is required when selected; SMTP server hostname verification is enabled.

Hikari is used for pooling. Set SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE and
SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE to values within the actual Supabase tier
connection budget, counting rollout overlap and any other consumers. Unset means
Hikari defaults (typically 10 max and matching minimum), not an audited capacity
choice. Use session mode for Flyway/JPA; do not substitute transaction port 6543
without separately proving compatibility.

Production currently forces sslmode=require (encryption, without server identity
verification). For verified TLS, obtain the CA for the chosen endpoint from
Supabase, add it as a Render secret file such as /etc/secrets/supabase-ca.crt,
and configure the JDBC URL with sslmode=verify-full&sslrootcert=/etc/secrets/supabase-ca.crt.
pgJDBC URL options take precedence over datasource properties; never include
sslmode=disable, ssl=false, or a nonvalidating SSL factory. Verify the certificate
and hostname against that exact endpoint before rollout. Supabase reference:
https://supabase.com/docs/guides/getting-started/quickstarts/spring-boot

Do not override server.forward-headers-strategy=NONE. The application resolves
client addresses separately. Do not set Hibernate ddl-auto to update/create or
Flyway baseline-on-migrate/clean to bypass a schema mismatch. Migrations V1–V6
contain schema changes, not demo employee/attendance inserts. Development fixtures
must never be loaded into the production database.

Leave all ADESA_BOOTSTRAP_ADMIN_* values absent during initial readiness checks.

## Office-network trust gate

With no trusted proxies configured, forwarding headers are ignored. Attendance
will generally be denied behind a hosting proxy; that is the safe initial state.
The resolver walks the full X-Forwarded-For chain right-to-left and selects the
first untrusted hop. It rejects malformed hops, including trailing empty hops,
and combines repeated header lines. It must never select the first header entry
just because it resembles the office address.

Obtain Render's documented/support-confirmed peer identities and header append
or overwrite behavior. A sampled address or observed browser public IP alone is
not sufficient proof of stable proxy trust. Current production validation accepts
only individual IPv4 proxy hosts, not dynamic ranges. Do not broaden that rule or
trust all private addresses to fit a platform whose peers cannot be pinned.

Before creating the admin, use provider-confirmed topology and a controlled,
non-production diagnostic deployment with no real users/data to inspect the peer
and full chain. Keep production attendance closed until that evidence is ready.
After explicitly approved first-admin provisioning, use the existing authenticated
GET /api/v1/admin/network/client-address from office and hotspot to corroborate it.
The endpoint remains ADMIN-only; no public diagnostic endpoint is added.

Required live evidence (with an approved test identity/device, separately approved
before writing attendance): office allowed; hotspot denied; hotspot denied with
spoofed X-Forwarded-For=105.127.6.174; repeated/spoofed header lines denied; behavior
persists after restart and routing changes. Test the direct Render URL as well as
any custom domain so an alternate ingress cannot bypass edge restrictions.

If Render cannot provide a stable, trustworthy path compatible with this policy,
stop rollout. Secure alternatives are an operator-controlled gateway reached
through an authenticated private/tunnel connection (with public origin access
blocked), or backend hosting with pinned ingress proxies and enforced origin
access. Do not merely insert a Vercel rewrite or read CF-Connecting-IP without
proving sender authentication, header sanitization and protection of every origin.

## Vercel (owner applies after approval)

Import the repository, choose frontend as Root Directory, Vite framework,
build command npm run build, output dist, and supported Node 22 or 24. Set only
VITE_API_BASE_URL to the actual backend HTTPS origin, without /api/v1. It is
embedded at build time, so changing it requires a frontend rebuild.
frontend/vercel.json provides history fallback; verify reload on /device and
password setup/recovery routes, not just the homepage. Vercel preview domains
are not automatically trusted by the backend.

The backend now permits credentialed CORS only for APP_PUBLIC_URL and the CSRF
header. CSRF remains enforced for mutations. The bootstrap returns a no-store
JSON token plus a backend-origin cookie; the frontend retains the token in memory
and echoes X-XSRF-TOKEN, with credentials: include. Sessions remain Secure and
HttpOnly, and the CSRF cookie is Secure in prod. No wildcard CORS is used.

Separate vercel.app/onrender.com sites require SameSite=None but browsers may
still block third-party cookies. Prefer frontend and API HTTPS subdomains of one
ADESA-owned domain, with SESSION_COOKIE_SAME_SITE=lax. This keeps requests direct
to Render without adding another IP-forwarding proxy. Confirm cookies, login,
logout, forced password change, WebAuthn and recovery in the supported browsers.
Do not change the passkey RP ID after onboarding without a migration plan.

## Backend readiness (owner authorizes deployment first)

Confirm startup logs show Flyway completed and Hibernate validation succeeded,
then check the actual backend origin:

```bash
curl --fail --show-error https://ACTUAL-BACKEND-HOST/actuator/health
```

Expected JSON includes status UP. Do not treat /health/liveness alone as proof of
DB readiness. In Supabase SQL Editor, these read-only checks verify migration
history and the selected schema; keep connection details out of shared output:

```sql
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;
SELECT table_name FROM information_schema.tables
WHERE table_schema = 'public' AND table_name IN
('employees','audit_logs','attendance_records','account_tokens');
```

Also confirm the application connection is TLS protected (operator session or
pg_stat_ssl for application sessions), effective pool limits, and successful mail
delivery. Static configuration inspection is not evidence of a live connection.

## First administrator (separate explicit approval required)

Ask for the intended email, name and employee number only when ready. Have the
operator generate a strong temporary password privately in a password manager and
enter all four ADESA_BOOTSTRAP_ADMIN_EMPLOYEE_NUMBER, _FULL_NAME, _EMAIL and
_PASSWORD values directly in Render. Never send the password in chat or place it
in shell arguments. An approved restart provisions one account and records
FIRST_ADMIN_BOOTSTRAPPED in the same transaction. Concurrent initializers are
serialized by a database lock, including initializers with different emails.
Any existing ADMIN (inactive included), or prior bootstrap audit event, blocks
repeat provisioning; bootstrap never reactivates accounts.

Sign in using the privately held temporary password, complete the forced password
change, and sign in again. Normal/admin endpoints remain denied until the change.
Verify the audit event and immediately remove all four bootstrap variables from
Render, then approve a restart and confirm no second account/event appears.
Register the admin passkey through the normal device flow if needed; employee
attendance requires an ADMIN-approved device. Do not auto-approve employees.

## Production acceptance record

Record date, deployed commit, browser, office/hotspot source, actor and evidence
for each item. Keep local automated tests in a separate section. None of these
live checks has been performed by this handover:

- Admin sign-in, employee CRUD, role restrictions, reports, corrections and audit.
- PDF remains /api/v1/admin/attendance/export; CSV is /export.csv and HR/ADMIN-only.
- Employee setup email, password selection, device registration and admin approval.
- Lagos workday: one sign-in, no second sign-in, no sign-out before sign-in, one
  sign-out, rejection of another sign-out. Use authorized acceptance identities.
- Office allowed; hotspot and spoofed-header attempts denied with a valid device.
- Other employees' records and admin endpoints inaccessible to an employee.
- Recovery email/token expiry/single use, credential invalidation and no secret logs.
- Approved restart preserves attendance, signs out sessions, never repeats bootstrap.

Run local verification from the repo with a working Docker daemon:

```bash
mvn -B -f backend/pom.xml verify
npm --prefix frontend test -- --maxWorkers=1
npm --prefix frontend run typecheck
npm --prefix frontend run build
```

A green local suite does not verify Supabase, Zoho or office/hotspot behavior.
