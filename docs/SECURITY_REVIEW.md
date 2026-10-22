# Security Review — ADESA HQ Attendance System

**Phase:** 8 (Security verification) and readiness input for Phase 9 (Pilot)
**Date:** 2026-10-22
**Reviewer:** Build agent, on behalf of ADESA IT
**Scope:** Backend (`backend/`), frontend (`frontend/`), deployment and configuration surface
(`docker-compose.yml`, `dev.sh`, `.env*`, `vite.config.ts`, `application*.yml`), and the Phase 8
changes made in response to this review.

> **Production is not yet verified.** This review confirms the design and the code-level controls.
> It does **not** replace the mandatory end-to-end verification from the real office network and from
> a genuinely different network (checklist items 2.10 and 2.11). Until those are done, the system is
> "pilot-ready pending network sign-off", not "production-verified".

> **Deployment values update:** the office IP quoted in the original review is historical and is not
> a production default. `application-prod.yml` now requires an explicit office CIDR, which must be
> selected only after the actual Render-resolved path has been verified. See the current procedure
> in `README.md`.

---

## 1. How this review was performed

Two independent, read-only audits were run in parallel, then every reported finding was re-checked
against the source before any change was made. Findings that did not survive re-checking are recorded
as **Rejected** rather than silently dropped.

| Audit | Surface | Files read in full |
|---|---|---|
| A — Backend | Java source, Spring Security config, persistence, network guard, auth/session, audit | all of `backend/src/main/java`, `application*.yml`, `pom.xml` |
| B — Frontend & infra | React/TypeScript, build config, container and dev-script configuration | all 44 files under `frontend/src`, `frontend/*`, `docker-compose.yml`, `dev.sh`, `.env*`, `.gitignore` |

**Threat model.** The assets are (a) the integrity of attendance records — the records must prove a
person was physically in the ADESA office — and (b) employee personal data (names, e-mail addresses,
password hashes, attendance times). The primary controls the system relies on are:

1. **Office-network enforcement** — an attendance action is only accepted when the resolved client
   address is in the narrow office allowlist confirmed by ADESA IT for the deployed path.
2. **Device binding** — an approved WebAuthn passkey tied to one employee.
3. **Authenticated, role-checked sessions** — server-side sessions, CSRF protection, method-level
   authorisation.
4. **An append-only audit trail** — every administrative and attendance action is recorded.

Adversaries considered: an unauthenticated internet attacker; an authenticated employee trying to
act for someone else or from outside the office; a curious or malicious insider with HR or ADMIN
rights; and an operator who misconfigures the deployment.

---

## 2. Fixes applied in this phase

Each fix below is a real, reproduced finding. Regression tests were added where the behaviour can be
tested locally.

### 2.1 Backend

| ID | Severity | Finding | Fix |
|---|---|---|---|
| B-H1 | High | `application.yml` shipped a usable default database password (`adesa_dev_password`) with no production guard, and `application-prod.yml` did not override the datasource at all. | `application-prod.yml` now sets `spring.datasource.password: ${DB_PASSWORD}` with **no default** (missing value = startup failure), and `ProductionSafetyValidator` refuses to start on the `prod` profile when the password is unset, blank, or still the development default. |
| B-M1 | Medium | The login rate limiter was keyed on the e-mail address alone, so anyone who knew an employee's e-mail could lock that account (including an administrator) out of the application. | The key is now `e-mail + client address`, resolved through the trusted-proxy-aware `ClientIpResolver`. Locking an account can now only be done from the attacker's own address. |
| B-M2 | Medium | The limiter's key map was unbounded, and every failed attempt — including for e-mail addresses that do not exist — wrote an audit row in a new transaction. An unauthenticated attacker could grow the heap without limit and use the endpoint as a database-write primitive. | The key set is bounded (`auth.login-max-tracked-keys`, default 10 000) with expired-entry sweeping; `LOGIN_FAILED` is only written for accounts that exist, and `LOGIN_RATE_LIMITED` is written **once**, at the transition into the blocked state, never per blocked request. |
| B-M3 | High (config) | No validation of the allowlist breadth: `ATTENDANCE_ALLOWED_IPV4=0.0.0.0/0` passed startup validation and silently approved the whole internet, while the app reported itself healthy. Trusted proxies could likewise be a whole network range. | `ProductionSafetyValidator` now rejects any allowlist entry broader than a `/24`, any non-IPv4 entry, and any trusted proxy that is not a single `/32` host address. |
| B-M4 | Medium | The default profile left the session and CSRF cookies non-`Secure`; `ProductionSafetyValidator` ran only when the literal `prod` profile was active and checked only the network settings. | The `prod` profile now fails startup unless `server.servlet.session.cookie.secure=true`; the CSRF cookie follows the same setting (`SecurityConfig`); and the validator's checks were extended (below). |
| B-M5 | Medium | Production did not validate the WebAuthn relying party: unset `WEBAUTHN_*` variables left the RP ID as `localhost` with a plain-HTTP origin and disabled user verification. | `prod` now requires a real (non-`localhost`, non-IP) RP ID, HTTPS origins that are within that RP ID, and `user-verification-required=true`; `application-prod.yml` defaults that last one to `true`. |
| B-M6 | Medium | The development bypass was only refused under the `prod` profile. A deployment with no profile but the bypass on would silently disable the office-network control. | Outside production the bypass is now refused unless the `dev` profile is explicitly active; `dev.sh` sets `SPRING_PROFILES_ACTIVE=dev`. |
| B-L1 | Low | Spring Boot created an unused in-memory user and printed a generated password to the log on every start. | `UserDetailsServiceAutoConfiguration` is excluded — the account was unreachable (form login and HTTP Basic are disabled) but the credential should never appear in a log. |
| B-L2 | Low | Log files `backend/logs/security.log`, `backend/target/security.log` and `logs/security.log.2026-10-21.0.gz` were group/world readable while containing client IPs and employee UUIDs. | All are now `640` under a `750` directory (matching the runbook requirement). |

### 2.2 Frontend

| ID | Severity | Finding | Fix |
|---|---|---|---|
| F-M1 | Medium | The API client had no request timeout: a stalled backend or proxy left the UI on "Loading…" forever with every control disabled. | 30-second hard deadline combined with any caller signal; a timeout surfaces as an `ApiError` with a user-facing message instead of a raw `DOMException`. |
| F-M2 | Low | `readCookie` could throw an unhandled `URIError` on a malformed `XSRF-TOKEN` value. | Malformed percent-encoding is caught and treated as "no cookie". |
| F-M3 | Low | `apiDownload` saved any 200 response as a `.pdf`, so an HTML error page could be saved as a report. | The response `Content-Type` is checked for `application/pdf`. |
| F-M4 | Low | Server-side field validation errors were parsed but never shown, so "e-mail already in use" looked like a generic failure. | `describeError` now appends the server's field-level detail; the login path is deliberately unchanged so it cannot be used to enumerate accounts. |
| F-L1 | Low | The admin attendance search issued a request on every keystroke. | 300 ms debounce. |
| F-L2 | Low | Credential and e-mail inputs lacked mobile autofill hints; iOS could auto-capitalise the first letter of a work e-mail. | `autoCapitalize="none" autoCorrect="off" spellCheck={false}` on e-mail fields; `autoComplete="new-password"` on the initial-password field. |
| F-L3 | Low | Newly revealed admin forms did not take focus, and cancelling dropped focus to `<body>`. | First field is focused on open; focus returns to the trigger on cancel. |
| F-L4 | Low | The CSV-named export function actually downloads a PDF. | Renamed to `exportAttendancePdf`. |

### 2.3 Deployment and configuration

| ID | Severity | Finding | Fix |
|---|---|---|---|
| D-H1 | High | `docker-compose.yml` published PostgreSQL on `0.0.0.0` with a default superuser password, so any host on the same network could read and write the database (password hashes, e-mail addresses, attendance, audit). | The port is bound to `127.0.0.1` and `POSTGRES_PASSWORD` is now required (`:?`), so the "safe" configuration is the default one. |
| D-M1 | Medium | The Vite dev server was published on `0.0.0.0` and served the raw source tree; the compose command rewrote `package-lock.json` by using `npm install`. | Loopback binding, `npm ci`, `host: '127.0.0.1'` and an explicit `allowedHosts` allowlist. |
| D-M2 | Medium | `.env` (plaintext developer credentials) was world-readable and `dev.sh` contained and printed a committed administrator password. | `.env` is `600`; the committed password literal is gone (the script now requires it to be set); the password is no longer printed. |
| D-L1 | Low | `dev.sh` used unscoped `pkill -f` patterns, `source`d `.env` as shell code, had no guard against a non-local database, interpolated an unvalidated device id into an admin request, and could leave an admin session cookie jar in `/tmp`. | PIDs are recorded and killed; `.env` is parsed safely; non-local `DB_URL` is refused; the device id is UUID-validated; a `trap` removes the cookie jar. |

### 2.4 Claims re-checked and rejected (recorded for honesty)

- **"The export endpoint is unbounded."** Reported by both audits; incorrect.
  `AttendanceReportService.MAX_EXPORT_ROWS` is `10 000`; the query fetches
  `MAX+1` rows and returns a 400 when the result is larger.
- **"Employee id is accepted from the client."** Incorrect. Every employee-facing attendance
  endpoint derives the identity from the authenticated principal and accepts no employee id
  (`AttendanceController`).
- **"The SPA enforces rules the backend does not."** Incorrect. Every UI rule that hides or
  disables a control has a server-side counterpart (verified endpoint by endpoint).
- **"The CSRF cookie was issued without `SameSite`."** Correct for the old code, but resolved:
  the repository now sets `SameSite=Lax` explicitly and follows the session cookie's `Secure`
  setting. Because forwarded-header processing is deliberately `NONE`, `CookieCsrfTokenRepository`
  would otherwise have derived `Secure` from `request.isSecure()` (always `false` behind the
  TLS-terminating proxy).
- **"The export endpoint has no audit entry."** Left as a low-priority observation rather than a
  fix: the export is a read of data the caller is already authorised to see, and the request is
  written to the application access log.

---

## 3. What is verified

| Area | Status | Evidence |
|---|---|---|
| Office-network enforcement, fail-closed | Verified | `OfficeNetworkPolicy`, `AttendanceNetworkGuard`, `AttendanceNetworkFailClosedTest`, `AttendanceTrustedProxyTest`, `ClientIpResolverTest` |
| Spoofed `X-Forwarded-For` cannot bypass | Verified | Trusted-proxy list is empty by default; a forwarded header is only honoured from a configured `/32` proxy; malformed chains fail closed |
| Admin accounts | Verified | `EmployeeServiceTest` (deactivation refused, last-active-admin guard, reactivation restores access without touching attendance) |
| CSRF | Verified | Cookie repository + SPA handler; login is CSRF-protected; regression test asserts the cookie is `Secure`/`SameSite=Lax`/readable-by-JS |
| Security headers | Verified | `frame-ancestors`/`X-Frame-Options: DENY`, `nosniff`, `Referrer-Policy: same-origin`, `Content-Security-Policy: default-src 'none'` — asserted by `AuthControllerTest` |
| Session management | Verified | Rotation on login, absolute timeout, credentials-version invalidation on password/role/active change |
| SQL injection / command injection / XXE / deserialisation / SSRF / open redirect / path traversal | Not present | No raw SQL, no shell-out, no XML endpoint, no Java native deserialisation, no outbound HTTP client, no redirect, no file upload |
| XSS (API) | Not present | JSON-only API; the CSP allows no scripts; the SPA renders all server strings through escaped JSX |
| Production fail-closed configuration | Verified | `ProductionSafetyValidatorTest` (20 cases) |
| Dependency audit | Verified | `npm audit` → 0 vulnerabilities |
| Backup / restore | Verified | Rehearsed into a scratch database; row counts and checksums matched; scratch database dropped; dump deleted (contained PII) |

**Test suites at the time of review:** backend **189 tests, 0 failures**; frontend **64 tests,
0 failures**, `tsc -b` clean, production build succeeds.

---

## 4. Residual risks and open decisions

None of these block the pilot, but each needs a decision recorded before general rollout. They are
deliberate design trade-offs or dependencies on ADESA IT, not overlooked defects.

1. **Administrators are password-only (no MFA).** A phished administrator password gives full
   control. The passkey is bound to attendance devices, not to sign-in. *Recommendation:* require a
   passkey (or TOTP) at sign-in for `ADMIN`/`HR`, or step-up re-authentication for password resets,
   role changes and device replacement.
2. **An administrator can reset any password and replace any device**, which lets one administrator
   act as another employee. The audit trail records the reset and the device change, so impersonation
   is detectable, but there is no separation of duties. *Recommendation:* ADESA decides whether to
   require a second approver or an out-of-band reset token.
3. **HR/ADMIN self-correction.** An HR/ADMIN user can correct their own attendance record. The change
   is recorded with the actor's identity in the audit trail, but there is no second approver.
   *Recommendation:* decide whether correcting your own record requires a second administrator.
4. **The attendance correction path is intentionally not network-restricted.** HR/ADMIN may correct
   records from anywhere. It cannot create a record (404 if none exists), is role-gated and is
   audited. This is a policy decision to confirm with ADESA.
5. **Reverse-proxy headers are undecided.** `server.forward-headers-strategy: NONE` is deliberate, so
   the application controls client-IP trust itself. The trusted-proxy list stays **empty** until ADESA
   IT confirms the hosting topology. HSTS and the SPA's `Content-Security-Policy` must be set at the
   edge (checklist items 1.9 and 3.3). Without HSTS, a user who types `http://` could have their
   session or CSRF cookie downgraded.
6. **Real-network verification is still outstanding.** The office allowlist and the "different
   network is refused" case must be confirmed against the deployed application from both networks
   (checklist 2.10/2.11).
7. **`recovery.admin@adesa.test`** still exists as a second active administrator with a development
   password. It must be removed or given a real, unique credential before the pilot.
8. **Multi-instance login limiting.** The limiter's state is per instance, so a horizontally scaled
   deployment needs a shared store (Redis) or sticky sessions. Documented as a scaling limitation.

---

## 5. Sign-off

| Item | State |
|---|---|
| Phase 8 code fixes | Complete; backend 189 / frontend 64 tests green |
| Regression tests for the fixes | Added (`SecurityConfigTest`, expanded `ProductionSafetyValidatorTest` and `LoginRateLimiterTest`, `client.test.ts`, `errors.test.ts`) |
| Documentation | `OPERATIONS_RUNBOOK.md`, `PILOT_PLAN.md`, `DEPLOYMENT_CHECKLIST.md` updated |
| Production verification from the office and from a different network | **Outstanding — requires ADESA IT** |
| Approval to start the pilot | Pending the checklist gates (see `PILOT_PLAN.md`) |

**Statement.** No critical finding remains. The two High backend findings and the one High
configuration finding have been fixed and covered by tests. The remaining items are documented
residual risks or ADESA IT dependencies. Production security will not be claimed as *verified* until
the deployed application is tested from both networks in section 4 item 6.
