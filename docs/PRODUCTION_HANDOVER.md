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
A push to the linked branch can trigger production deployment. Confirm the
current auto-deploy setting before pushing. No Docker command override is needed.

Known non-secret values:

```dotenv
SPRING_PROFILES_ACTIVE=prod
ATTENDANCE_TIMEZONE=Africa/Lagos
ATTENDANCE_GEOLOCATION_ENABLED=true
ATTENDANCE_AUTHENTICATED_INGRESS_ENABLED=true
ATTENDANCE_NETWORK_ENFORCEMENT_ENABLED=false
ATTENDANCE_NETWORK_LOCAL_DEVELOPMENT_BYPASS=false
ATTENDANCE_CLIENT_IP_HEADER=X-Forwarded-For
WEBAUTHN_USER_VERIFICATION_REQUIRED=true
MAIL_PROVIDER=zoho-api
```

See [Office location for attendance](OFFICE_LOCATION_FALLBACK.md) for the configured radius and device verification steps.

Values the owner must obtain (not guesses):

| Variable | Source / requirement |
| --- | --- |
| DB_URL | Supabase Connect: JDBC URL, no embedded credentials; session pooler port 5432 or reachable direct endpoint |
| DB_USERNAME | Exact selected connection username, including project suffix where supplied |
| DB_PASSWORD | Newly rotated database password, entered privately in Render |
| APP_PUBLIC_URL | Exact HTTPS frontend origin, no path/query; also the sole allowed credentialed CORS origin |
| WEBAUTHN_RP_ID | Frontend hostname only, no scheme/path; choose final domain before registering passkeys |
| WEBAUTHN_ALLOWED_ORIGINS | Exact HTTPS frontend origin(s) valid for that RP ID; no wildcard or preview domains |
| ATTENDANCE_INGRESS_SHARED_SECRET | Private 64-character hex key matching the Vercel gateway |
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

## Attendance ingress

Production clocks in and out through the authenticated Vercel gateway. Keep
the ingress secrets matched and the trusted XFF proxy list empty. Use the
[office location verification steps](OFFICE_LOCATION_FALLBACK.md) for release
acceptance. Include a direct Render request in the access checks to verify that
attendance remains restricted to authenticated ingress.

## Vercel (owner applies after approval)

Run the CLI from `frontend` for the existing Vite project with Root Directory `.`, build command
`npm run build`, output directory `dist`, and Node 24. Use
`VITE_API_BASE_URL=https://adesa-attendance-frontend.vercel.app` or leave it
empty in Production. Browser requests reach the
same-origin `/api/v1/...` gateway in `frontend/vercel.json`. It forwards
to `https://adesa-attendance-system.onrender.com`. Vercel's server-only
`INGRESS_SHARED_SECRET` must match Render's `ATTENDANCE_INGRESS_SHARED_SECRET`.

Test the production hostname so WebAuthn uses its registered RP ID and origin.
Check session cookies, CSRF, and history fallback on reloads. Use the release
order in [Office location for attendance](OFFICE_LOCATION_FALLBACK.md).

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
- On-site clock-in and clock-out succeed with persistent Safari permission. Out-of-radius attempts and direct backend requests without authenticated ingress are denied.
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

## Historical gateway rollout and office-IP update — 2026-10-23

The authenticated Vercel gateway is deployed. API requests now use the frontend
hostname. The owner explicitly approved replacing the original provisional
105.127.6.174/32 with observed office egress 105.127.8.52/32. Only this exact host
was changed; enforcement and authenticated ingress stay enabled, development
bypass stays off and trusted XFF proxies stay empty. ISP static/dedicated status
has not been independently confirmed; future address changes must fail closed.

Older open frontend tabs may retain the direct-Render API URL and produce
CLIENT_ADDRESS_UNRESOLVED. Hard-refresh the frontend and sign in again, then
inspect /api/v1/admin/network/client-address on the FRONTEND hostname. Run
`python3 scripts/check_office_network.py --gateway` from office Wi-Fi and hotspot
to validate actual authenticated addresses and forged-header rejection. A successful
deployment or a public CSRF check does not replace these live acceptance checks.
