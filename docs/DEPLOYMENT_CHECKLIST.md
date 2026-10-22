# ADESA HQ Attendance — Deployment Checklist

This checklist separates what has been **verified in the repository** (automated
tests, static configuration) from what still has to be **verified manually on
the target infrastructure** by ADESA IT.

Legend:

- **[A] Verified automatically** — covered by the test suite
  (`cd backend && mvn test`); no site access needed.
- **[M] Manual verification required** — must be executed on the real
  deployment by ADESA IT; it cannot be proven from the repository.
- **[A] + [M]** — the logic is tested automatically *and* has to be confirmed in
  the real environment.

> The office-network enforcement is **off by default** and is never enabled
> implicitly. Items marked [M] in section 2 are the gate before it may be
> switched on in production.

---

## 1. Hosting and configuration

| # | Item | Status | How to verify | Owner |
|---|---|---|---|---|
| 1.1 | The allowlist is configuration, not code (`attendance.network.allowed-ipv4` ← `ATTENDANCE_ALLOWED_IPV4`) | [A] | `OfficeNetworkPolicyTest`; grep the source for a hard-coded address | Dev |
| 1.2 | Default value is the ADESA HQ public IPv4 `105.127.6.174/32` | [A] | `application.yml`, `.env.example` | Dev |
| 1.3 | Enforcement is **off** by default and never enabled implicitly | [A] | `ProductionSafetyValidatorTest` (`prodProfileDoesNotEnableEnforcementByDefault`) | Dev |
| 1.4 | The `prod` profile refuses to start without enforcement + an allowlist | [A] | `ProductionSafetyValidatorTest` | Dev |
| 1.4a | A disabled policy **fails closed** (HTTP 403) when no explicit local-development bypass is set | [A] | `AttendanceNetworkFailClosedTest`, `AttendanceNetworkGuardTest.disabledPolicyWithoutBypassFailsClosed` | Dev |
| 1.4b | The `prod` profile refuses to start when the local-development bypass is set | [A] | `ProductionSafetyValidatorTest.prodWithLocalDevelopmentBypassFails` | Dev |
| 1.5 | No private/laptop/link-local address is on the allowlist | [A] | `OfficeNetworkPolicyTest.theOfficePublicAddressIsTheOnlyApprovedAddress` | Dev |
| 1.6 | The application runs on a public cloud server with HTTPS only | [M] | Certificate, TLS termination, redirect to HTTPS | ADESA IT |
| 1.7 | `WEBAUTHN_RP_ID` / `WEBAUTHN_ALLOWED_ORIGINS` match the deployed domain | [M] | Passkey registration from the deployed domain | ADESA IT |
| 1.8 | Database user is least-privilege, backups run and a restore was tested | [M] | `pg_dump` + restore rehearsal | ADESA IT |
| 1.9 | The built frontend is served from the same origin as the API, with an SPA fallback to `index.html` and `/api` forwarded to the backend | [M] | `npm run build` → `frontend/dist`; open `/device` directly and reload a deep link | ADESA IT |
| 1.10 | The frontend build is reproducible (`npm ci && npm run build`) and the type check passes | [A] | `npm run typecheck`, `npm run build`, `npm test` | Dev |
| 1.11 | The development database is bound to loopback and has **no default password** (`POSTGRES_PASSWORD` is required) | [A] | `docker-compose.yml` | Dev |
| 1.12 | `.env` and the security log are not world-readable, and no development password is committed | [A] | `dev.sh` (`chmod 600`/`640`, no password literal), `.env.example` | Dev |
| 1.13 | The edge sends `Strict-Transport-Security`; plain HTTP is redirected | [M] | `curl -sI https://…` and `curl -sI http://…` | ADESA IT |
| 1.14 | A database backup was taken **and restored into a scratch database** with matching counts and Flyway history | [A] + [M] | Rehearsal record in `docs/OPERATIONS_RUNBOOK.md` §7.3; repeat on production | ADESA IT |
| 1.15 | The `prod` profile refuses to start with the development database password, a non-`Secure` session cookie, or a placeholder/insecure WebAuthn configuration | [A] | `ProductionSafetyValidatorTest` | Dev |
| 1.16 | The allowlist cannot be broader than a `/24`, and trusted proxies must be single `/32` host addresses | [A] | `ProductionSafetyValidatorTest.prodWithOverBroadCidrFails`, `…prodWithTrustedProxyRangeFails` | Dev |
| 1.17 | The local-development bypass is refused unless the `dev` profile is explicitly active | [A] | `ProductionSafetyValidatorTest.bypassWithoutDevProfileFails` | Dev |
| 1.18 | A compromised e-mail address can no longer lock an account out globally (login limiting is per account **and** client address, and its state is bounded) | [A] | `LoginRateLimiterTest.trackedKeysAreBounded`, `AuthControllerTest` | Dev |

## 2. Office-network enforcement (the production gate)

| # | Item | Status | How to verify | Owner |
|---|---|---|---|---|
| 2.1 | A request from the approved address is accepted | [A] | `AttendanceNetworkRestrictionTest.signInFromTheApprovedOfficeAddressAccepted` | Dev |
| 2.2 | A request from an external IPv4 address is rejected (HTTP 403) | [A] | `…signInOutsideTheApprovedNetworkRejected`, `…signInFromANeighbouringPublicAddressRejected` | Dev |
| 2.3 | A request without client-IP information is rejected (fail closed) | [A] | `…signInWithoutClientAddressRejected`, `…signInWithMalformedClientAddressRejected` | Dev |
| 2.4 | A spoofed forwarding header from an untrusted peer does not bypass the policy | [A] | `…spoofedForwardingHeaderRejected`, `AttendanceTrustedProxyTest` | Dev |
| 2.5 | A trusted proxy is required for the forwarded address to be used | [A] | `AttendanceTrustedProxyTest.officeAddressForwardedByTrustedProxyAccepted` | Dev |
| 2.6 | IPv6 traffic cannot bypass the IPv4-only policy | [A] | `…signInFromAnIpv6ClientRejected`, `OfficeNetworkPolicyTest.anIpv6RangeInTheAllowlistFailsStartup` | Dev |
| 2.7 | Denied attempts are written to the restricted security log (client IP, timestamp, reason) | [A] | `…deniedAttemptIsWrittenToTheSecurityLog` | Dev |
| 2.8 | **Hosting topology identified**: direct exposure or platform load balancer / reverse proxy in front of the application | [M] | Cloud console / provider documentation | ADESA IT |
| 2.9 | **Trusted proxy addresses configured** — only if 2.8 shows a proxy. Never invented | [M] | `ATTENDANCE_TRUSTED_PROXY_ADDRESSES` + diagnostics endpoint | ADESA IT |
| 2.10 | **Resolved client IP confirmed from ADESA HQ Wi-Fi** — the diagnostics endpoint must report `105.127.6.174` | [M] | `GET /api/v1/admin/network/client-address` from HQ Wi-Fi | ADESA IT |
| 2.11 | **External hotspot denied** — the same request from a mobile hotspot must be refused with HTTP 403 | [M] | `POST /api/v1/attendance/sign-in` from a hotspot | ADESA IT |
| 2.12 | Security log file permissions restricted to the operations/IT account | [M] | `ls -l logs/security.log`, group/ACL review | ADESA IT |
| 2.13 | Enforcement enabled in production **after** 2.10 and 2.11 succeeded | [M] | `ATTENDANCE_NETWORK_ENFORCEMENT_ENABLED=true` + restart | ADESA IT |

## 3. Additional security layers (network edge)

| # | Item | Status | How to verify | Owner |
|---|---|---|---|---|
| 3.1 | Cloud firewall / security group restricts the attendance endpoints to the expected sources | [M] | Firewall rules review | ADESA IT |
| 3.2 | The edge (load balancer, WAF, ingress) does not strip or rewrite the client-address header in an unexpected way | [M] | Compare the diagnostics output with the edge logs | ADESA IT |
| 3.3 | Only HTTPS reaches the application; plain HTTP is redirected or blocked | [M] | `curl -I http://…` | ADESA IT |
| 3.4 | The application binds to a private interface where possible, or the edge is the only public entry point | [M] | Infrastructure review | ADESA IT |
| 3.5 | Rate limiting / WAF rules in front of the login endpoint | [M] | Load test + WAF rule review | ADESA IT |
| 3.6 | VPN routes that must not qualify as "the office network" are excluded | [M] | Policy decision + configuration | ADESA IT / HR |

## 4. Application security (verified in the repository)

| # | Item | Status | How to verify | Owner |
|---|---|---|---|---|
| 4.1 | Sessions are server-side, `HttpOnly`, `SameSite=Lax`, `Secure` in prod; idle and absolute timeouts enforced | [A] | `AuthControllerTest` | Dev |
| 4.2 | CSRF token required on every state-changing request | [A] | `AuthControllerTest` | Dev |
| 4.3 | Sign-in rotation, rate limiting (HTTP 429), no user enumeration | [A] | `LoginRateLimiterTest`, `AuthControllerTest` | Dev |
| 4.4 | WebAuthn ceremonies: single-use challenges, session-bound single-use device proof, origin/rp-id checks | [A] | `WebAuthnFlowTest` | Dev |
| 4.5 | Only an approved registered device can record attendance; device approval is ADMIN-only | [A] | `AttendanceApiTest`, `AdminEmployeeControllerTest` | Dev |
| 4.6 | Attendance identity comes from the session only — no employee id in any request | [A] | `AttendanceApiTest` | Dev |
| 4.7 | One record per workday, enforced by a unique constraint; concurrent sign-ins cannot duplicate | [A] | `AttendanceApiTest` | Dev |
| 4.8 | Workday computed in `ATTENDANCE_TIMEZONE` (`Africa/Lagos`), never from the device clock | [A] | `AttendanceWorkdayTest` | Dev |
| 4.9 | Rejected and successful attendance actions are audited; no secrets in the audit trail | [A] | `AttendanceApiTest`, `AttendanceNetworkRestrictionTest` | Dev |
| 4.10 | Role separation EMPLOYEE / HR / ADMIN enforced server-side | [A] | `AdminEmployeeControllerTest` | Dev |
| 4.11 | No secret, password hash or token is written to any log | [A] | code review + log assertions | Dev |
| 4.12 | The frontend is not a security boundary: the backend decides every permission | [A] | `DashboardPage.test.tsx`, `RequireAuth.test.tsx` | Dev |
| 4.13 | The frontend stores no credential material (session in an `HttpOnly` cookie, CSRF token echoed in the header) | [A] | `client.test.ts` | Dev |
| 4.14 | The correct attendance button is shown per state, pending actions block repeated clicks, errors are displayed and the state is refreshed after an action | [A] | `DashboardPage.test.tsx` (9 tests) | Dev |
| 4.15 | Protected screens cannot be reached by changing the URL | [A] | `RequireAuth.test.tsx` | Dev |
| 4.16 | Role separation is exact: HR may read the roster, the attendance reports and the attendance/device audit entries, but gets no account management, no device administration and no network diagnostics | [A] | `AdminAttendanceApiTest.hrKeepsNoAdministrationPowers`, `…auditVisibilityDependsOnRole` | Dev |
| 4.17 | An attendance correction needs a mandatory reason, is refused without one, and is transactional (record + correction history + audit entry commit together) | [A] | `AdminAttendanceApiTest.correctionIsAppliedAndAudited`, `…correctionWithoutReasonIsRejected` | Dev |
| 4.18 | A correction never destroys the previous values (they stay in `attendance_corrections`) and the CSV export neutralises spreadsheet formulas | [A] | `AdminAttendanceApiTest`, `AttendanceCsvWriterTest` | Dev |
| 4.19 | The HR and administrator screens are role-guarded in the frontend, and the backend refuses the request in any case | [A] | `RequireRole.test.tsx`, `DevicesPage.test.tsx`, `EmployeesPage.test.tsx` | Dev |
| 4.20 | An administrator cannot be deactivated through employee management; the attempt is refused (409) and audited | [A] | `EmployeeServiceTest.adminDeactivationThroughUpdateIsRejected`, `AdminEmployeeControllerTest.administratorCannotBeDeactivatedThroughUpdate` | Dev |
| 4.21 | An authorized administrator can reactivate a deactivated administrator; unauthorized callers cannot | [A] | `EmployeeServiceTest.reactivateInactiveAdmin`, `AdminEmployeeControllerTest.administratorCanBeReactivated`, `…reactivationRequiresAdminRole` | Dev |
| 4.22 | The system cannot be left without an active administrator (last active admin cannot be demoted) | [A] | `EmployeeServiceTest.lastActiveAdminRoleChangeIsRejected` | Dev |
| 4.23 | Reactivation only flips the active flag — historical attendance records are untouched | [A] | `AdminEmployeeControllerTest.administratorCanBeReactivated` | Dev |
| 4.24 | An inactive administrator does not block the controlled bootstrap recovery path | [A] | `FirstAdminBootstrapTest.bootstrapCreatesAdminWhenOnlyAdminIsInactive` | Dev |
| 4.25 | The `XSRF-TOKEN` cookie is `Secure` and `SameSite=Lax` when the session cookie is secure (including behind a TLS-terminating proxy) | [A] | `SecurityConfigTest` | Dev |
| 4.26 | API responses carry `X-Content-Type-Options`, `Referrer-Policy`, `X-Frame-Options: DENY` and a restrictive CSP | [A] | `SecurityConfigTest` | Dev |
| 4.27 | The SPA request client has a deadline; a stalled backend shows an error instead of hanging forever | [A] | `client.test.ts` (timeout test) | Dev |
| 4.28 | Validation errors name the rejected field; login errors never reveal whether an account exists | [A] | `errors.test.ts` | Dev |
| 4.29 | The report export is capped at 10,000 rows and a non-PDF download response is rejected | [A] | `AttendanceReportService.MAX_EXPORT_ROWS`, `client.test.ts` | Dev |
| 4.30 | A malformed CSRF cookie is ignored rather than crashing the client | [A] | `client.test.ts` (malformed cookie test) | Dev |
| 4.31 | The build serves no source maps and ships no development password or secret | [A] | inspect `frontend/dist`; grep the bundle | Dev |
| 4.32 | An unused generated in-memory user is not created and no generated password is written to the log | [A] | `UserDetailsServiceAutoConfiguration` excluded; start the app and inspect the log | Dev |

## 5. Operations and acceptance

| # | Item | Status | How to verify | Owner |
|---|---|---|---|---|
| 5.1 | Working hours, late/early rules and absence derivation agreed | [M] | HR decision (open point 3) | ADESA HR |
| 5.2 | Correction workflow (who may correct a record, with which reason) agreed — the mechanism is implemented (HR and ADMIN, mandatory reason, full history), the policy details are still open | [M] | HR decision (open point 10) | ADESA HR |
| 5.3 | Retention period for attendance records and audit logs agreed | [M] | ADESA decision (open point 6) | ADESA |
| 5.4 | Support and escalation path for a locked-out employee documented | [A] + [M] | `docs/OPERATIONS_RUNBOOK.md` §2 and §4; confirm the contact details before the pilot | ADESA IT |
| 5.5 | Pilot group and success criteria defined | [A] + [M] | `docs/PILOT_PLAN.md`; ADESA confirms the cohort and dates | ADESA |
| 5.6 | Security review of the whole system completed | [A] + [M] | `docs/SECURITY_REVIEW.md` (Phase 8); residual network items 2.10/2.11 remain | ADESA IT |
