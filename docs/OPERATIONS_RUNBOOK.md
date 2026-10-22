# ADESA HQ Attendance — Operations Runbook

Owner: ADESA IT. Applies to the production deployment (and, where noted, to the
pilot). Pair with [`DEPLOYMENT_CHECKLIST.md`](DEPLOYMENT_CHECKLIST.md) and
[`PILOT_PLAN.md`](PILOT_PLAN.md).

> **Two rules that override everything else**
> 1. Never delete or edit an attendance record directly in the database. Use the
>    correction workflow (HR/ADMIN, mandatory reason, full history).
> 2. Never disable the office-network enforcement to "make something work".
>    With enforcement off the system **fails closed** — attendance stops
>    entirely — and the `prod` profile refuses to start without it. If office
>    attendance must continue from a new network, add the confirmed address to
>    the allowlist; do not turn the control off.

---

## 1. System at a glance

| Component | What it is | Where the evidence lives |
|---|---|---|
| Backend | Spring Boot REST API, server-side sessions, WebAuthn, office-network guard | application log, `logs/security.log` |
| Database | PostgreSQL; employees, attendance, devices, corrections, audit | `attendance_records`, `audit_logs`, `attendance_corrections` |
| Frontend | React SPA served from the same origin as the API | browser |
| Edge | HTTPS termination, WAF/firewall, SPA fallback | provider console |
| Security log | Restricted file with refused attendance attempts (client IP, reason) | `SECURITY_LOG_FILE` (IT-only) |

Health check: `GET /actuator/health` → `{"status":"UP"}`.

## 2. Contacts and escalation

**[ADESA decision — fill in before the pilot]**

| Level | Who | When | Contact |
|---|---|---|---|
| L1 | Attendance support (HR/IT helpdesk) | employee cannot sign in; device approval; how-to | phone / mailbox |
| L2 | ADESA IT system owner | repeated failures, deployment, database, network | phone / mailbox |
| L3 | Application maintainer | defect, security incident, data integrity | escalation contact |
| Security | ADESA IT security | suspected credential/device compromise, unauthorised access | incident channel |

Escalate to L3/security **immediately** (do not wait for the daily review) for:

- an attendance action recorded from an unapproved network;
- an attendance record changed without a correction entry;
- a lost/stolen approved device;
- a suspected compromised administrator account;
- the audit trail or security log missing entries.

## 3. Daily operations checklist

Every workday, ADESA IT (or the named support person):

1. `GET /actuator/health` returns `UP`.
2. Review new `event=attendance_denied` lines in the security log. Expected:
   refusals from known non-office addresses. Unexpected: a refusal from
   `105.127.6.174` (the office) — escalate, the allowlist or topology changed.
3. ADMIN checks the audit trail for the previous day: account changes, device
   approvals/revocations/replacements, corrections, reactivations.
4. HR reconciles the attendance report against the manual register (pilot).
5. Confirm the nightly database backup completed and its age is < 24 h.
6. Check free disk space (database, logs) and the certificate expiry date.

Weekly: review failed-login volume (`429` responses), review inactive
accounts/devices, and prune nothing — retention is an ADESA decision.

## 4. Routine procedures

All UI actions require an account with the right role (ADMIN for accounts and
devices, HR/ADMIN for attendance). The API enforces the same rules.

### 4.1 Create an employee account (ADMIN)

UI: **Employees → Add employee**. Fields: employee number, full name, work
email, initial password (12–128 characters), role.

The employee must change the password after the first sign-in. The creation is
audited (`EMPLOYEE_CREATED`).

### 4.2 Reset a forgotten password (ADMIN)

UI: **Employees →** edit the account → set a new password. A password change
invalidates every existing session for that employee.

There is no self-service password reset and no email reset link. This is
deliberate: the support desk verifies the person.

### 4.3 Register and approve a device (employee + ADMIN)

1. The employee signs in and opens **Device setup**, then registers a passkey on
   the device (phone fingerprint/face or laptop platform authenticator).
2. The device appears as **PENDING_APPROVAL**.
3. ADMIN opens **Devices**, verifies the employee and the device label, and
   approves it.

An employee cannot record attendance until a device is approved. Never approve a
device you cannot attribute to the named employee.

### 4.4 Replace a lost or stolen device (ADMIN)

UI: **Devices → Replace** (with a reason). This ends the employee's sessions and
lets them register a new device. If the device was stolen, also treat it as a
security incident (section 5.6).

### 4.5 Revoke a device (ADMIN)

UI: **Devices → Revoke** (with a reason). Use for a compromised or
no-longer-authorized device. Sessions end immediately.

### 4.6 Correct an attendance record (HR/ADMIN)

UI: **Attendance → Records → Correct**. Set the sign-in/sign-out time and give a
reason of at least 10 characters.

- The previous values are preserved in `attendance_corrections`.
- The change is audited (`ATTENDANCE_CORRECTED`).
- A correction never creates a record for a day with no sign-in. If the employee
  never signed in, that is a policy/absence question for HR, not a correction.
- A correction is refused when the sign-out precedes the sign-in.

### 4.7 Export a report (HR/ADMIN)

UI: **Attendance → Records → Export as PDF**. The export is refused if the
filter matches more than 10,000 records; narrow the date range.

Treat the exported file as personal data: store it on the ADESA file share, not
in personal downloads or email.

### 4.8 Reactivate a deactivated account (ADMIN)

UI: **Employees → Reactivate** (shown for inactive accounts).

- Administrators cannot be deactivated through the normal employee screen; the
  backend refuses with HTTP 409 and audits the attempt.
- An inactive administrator can be restored by an **authorized administrator**
  through this dedicated action.
- Reactivation only flips the active flag. Attendance history is untouched and
  the action is audited (`EMPLOYEE_REACTIVATED`).

### 4.9 Recover when the only administrator is inactive

The bootstrap runs when **no active ADMIN exists**. Start the application once
with the four `ADESA_BOOTSTRAP_ADMIN_*` variables set (see README, "Creating the
first administrator"), then **unset them immediately** and change the password
after the first sign-in.

This is the only sanctioned recovery path. Do not update the database directly.

## 5. Incident procedures

### 5.1 An employee cannot sign in

1. Confirm the account is active (Employees screen).
2. Confirm the device is **APPROVED** (Devices screen).
3. Confirm the employee is on the **office network** (an outside attempt is
   refused by design; the message says so).
4. If the password is forgotten, reset it (4.2). If the device is lost, replace
   it (4.4).
5. If all four are correct, capture the time, the employee number and the exact
   message and escalate to L2.

Never ask for the employee's password.

### 5.2 A sign-in was refused from the office

1. Check the security log line: `action`, `reason`, `client_ip`.
2. If `client_ip` is not `105.127.6.174`, the topology changed (new ISP address,
   proxy, VPN) — this is a network change, not an employee error.
3. If a proxy was introduced, add its confirmed address to
   `ATTENDANCE_TRUSTED_PROXY_ADDRESSES`, verify with the diagnostics endpoint
   (`GET /api/v1/admin/network/client-address`) **from the office**, then
   restart.
4. If the office ISP address changed, ADESA IT confirms the new address in
   writing and updates `ATTENDANCE_ALLOWED_IPV4`. Never guess an address.
5. Do not disable enforcement.

### 5.3 The office internet connection is down

Attendance cannot be recorded until connectivity returns; the system fails
closed by design. Employees remain at work — HR records the day manually and
corrects the records afterwards with a reason, or accepts the manual register
for that day per HR policy. Do not create attendance rows by hand.

### 5.4 The application is unavailable

1. Check `/actuator/health` and the provider status page.
2. Check the application log for startup/DB errors.
3. Restart the service only after capturing the log tail.
4. If the database is unreachable, follow the provider's recovery procedure and
   then verify with a restore rehearsal (section 7).
5. Communicate to HR: which workday is affected, what the manual fallback is.

### 5.5 Suspected unauthorised access from outside the office

1. Do **not** change data. Preserve the security log and the audit trail.
2. Identify the account(s) and device(s) involved via the audit trail.
3. Revoke the affected devices and deactivate non-administrator accounts.
   Administrators are protected from deactivation; use the reactivation endpoint
   and the bootstrap recovery path if an administrator account is suspect.
4. Force a password change (invalidates sessions).
5. Escalate to ADESA IT security with the log excerpt and the timeline.
6. Confirm that no attendance record was created by the incident; if one was,
   correct it through the workflow and document the reason.

### 5.6 Lost or stolen approved device

1. **Replace or revoke** the device immediately (4.4 / 4.5). Sessions end.
2. The passkey may be synced to another device by the platform; if strict
   device binding is required, that is an ADESA IT decision (the current
   implementation cannot prove one physical device).
3. Record the incident and notify security.

### 5.7 Compromised administrator account

1. Reset the password (invalidates sessions).
2. Review the audit trail for actions by that administrator (account, device,
   correction, reactivation).
3. Ensure at least one other **active** administrator exists. If none does, use
   the bootstrap recovery path (4.9).
4. Escalate to security.

### 5.8 Emergency change to the network policy

Only ADESA IT, only with a written address confirmation, only through
configuration + restart. Changing enforcement off is **not** an emergency
option: with enforcement off (and no dev bypass) the system refuses every
attendance action, and the `prod` profile will not start. "Failing closed" is
the intended safe state.

## 6. Monitoring and logs

| Signal | Where | Alert |
|---|---|---|
| Application up | `/actuator/health` (liveness/readiness) | page L2 after 5 min down |
| Refused attendance | security log | daily review; immediate for office-address refusals |
| Failed logins | application log, HTTP 429 | spike → possible brute force |
| Database | provider metrics | disk > 80%, connections near limit |
| Certificate | provider | expiry < 30 days |
| Backups | provider / cron | missing or > 24 h old |
| Disk | host | > 80% |

**Log hygiene.** The security log contains client IP addresses and employee
UUIDs; it must be readable only by the IT operations account (`chmod 640`,
directory `750`). It never contains passwords, tokens, passkeys or device
proofs. Retention is an ADESA decision (checklist 5.3); nothing is deleted
automatically.

## 7. Backup and restore

### 7.1 What to back up

- The PostgreSQL database (the only stateful component). Sessions live in
  memory and do not need a backup; users simply sign in again.
- The deployment configuration (environment variables / secrets) in the
  provider's secret store — never in this repository.

### 7.2 Backup

```bash
# Nightly, from the host that can reach the database:
pg_dump -U adesa_app -d adesa_attendance -Fc -f /backups/adesa_attendance_$(date -u +%Y%m%dT%H%M%SZ).dump
```

Store the dump off the application host (object storage with encryption at
rest), keep at least 30 daily copies, and restrict access to the IT account.

### 7.3 Restore rehearsal (verified procedure)

Never rehearse against the live database. Restore into a **scratch** database
and compare:

```bash
createdb -U adesa_app adesa_restore_check
pg_restore -U adesa_app -d adesa_restore_check --no-owner --no-privileges backup.dump
psql -U adesa_app -d adesa_restore_check -c \
  "select count(*) from employees; select count(*) from attendance_records; \
   select count(*) from attendance_corrections; select count(*) from audit_logs;"
psql -U adesa_app -d adesa_restore_check -c \
  "select version, success from flyway_schema_history order by installed_rank;"
dropdb -U adesa_app adesa_restore_check
```

A rehearsal passes when the table counts and the Flyway history match the
source, and the application can start against the restored database in a
staging environment.

**Rehearsal record.** 2026-10-22, development database
(`adesa-attendance-postgres`): `pg_dump -Fc` → `pg_restore` into a scratch
database → table counts and row-level checksums for `employees` and
`attendance_records` matched the source exactly; Flyway history `1..5` all
`success = t`; scratch database dropped afterwards. The live data was not
modified. Repeat on the production database before the pilot (checklist 1.8).

### 7.4 Restore after data loss

1. Stop the application (do not let it write during the restore).
2. Take a fresh dump of the damaged database **before** restoring (evidence).
3. Restore the chosen backup into the production database.
4. Verify Flyway history and table counts.
5. Start the application, check `/actuator/health`, then verify a known record
   and the audit trail.
6. Report the incident and the data-loss window to HR.

## 8. Change management

| Change | Procedure |
|---|---|
| Application version | build → tests green → staging smoke test → deploy → health check → pilot cohort notified |
| Database migration | Flyway runs at startup; take a backup first; never edit a migration that has already run |
| Allowlist / trusted proxy | written address confirmation → update config → restart → verify from the office and from a hotspot |
| WebAuthn domain | update `WEBAUTHN_RP_ID` / `WEBAUTHN_ALLOWED_ORIGINS` → restart → test a real registration and assertion |
| Administrator accounts | keep at least two active administrators; never share credentials |

Every change is recorded with date, change, operator, reason and verification.

## 9. Known limitations (operational)

- Office-network access does not prove physical presence (a VPN that egresses
  through the office address qualifies). ADESA IT defines which routes count.
- A passkey may be synced by the platform; it does not prove one specific
  physical device.
- Sessions and the login rate limiter are in-memory: a multi-instance deployment
  needs a shared session store and a shared/WAF rate limit.
- The attendance engine records sign-in/sign-out only. Late/early/absence rules
  are an HR decision.
- The PDF export is capped at 10,000 rows per request.
- The security log is a file on the application host; it must be shipped to
  central logging if the host is ephemeral.
