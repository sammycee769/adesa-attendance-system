# ADESA HQ Attendance — Pilot Plan (Phase 9)

Status: **draft for ADESA approval**. The technical work needed to run the pilot
is complete; the decisions marked **[ADESA decision]** belong to ADESA HR /
management and must be confirmed before the pilot starts.

This plan is the Phase 9 counterpart of the security review
([`SECURITY_REVIEW.md`](SECURITY_REVIEW.md)) and the deployment checklist
([`DEPLOYMENT_CHECKLIST.md`](DEPLOYMENT_CHECKLIST.md)). It does **not** replace
them: the pilot may only start when every go-live gate in section 3 is green.

---

## 1. Purpose

Prove, with a small real cohort, that the attendance system works in the ADESA
HQ environment **before** it becomes the company-wide record of attendance.

The pilot answers five questions:

1. Can employees reliably sign in and out from the office network with their
   own phone/laptop passkey?
2. Do HR and ADMIN have what they need to run attendance day to day?
3. Is the office-network restriction correct in the real topology (it cannot be
   proven from a developer machine)?
4. Are the attendance records and the audit trail complete and trustworthy?
5. Is the support/escalation path workable when something goes wrong?

## 2. Scope

**In scope**

- The production deployment (public cloud server, ADESA domain, HTTPS).
- A pilot cohort of employees, one HR officer and at least one administrator.
- Sign-in/sign-out, device registration and approval, the employee history, the
  HR attendance report, corrections, the audit trail and the PDF export.
- The manual network verification from ADESA HQ Wi-Fi and from a mobile hotspot
  (deployment checklist items 2.10 / 2.11).

**Out of scope for the pilot**

- Payroll, leave, overtime and absence deduction. The system records
  sign-in/sign-out only and never marks anyone absent.
- Company-wide rollout (that is the post-pilot decision).
- Automated device replacement requests (still an administrator action).

## 3. Entry criteria (go/no-go gate)

All of these must be true before the first pilot employee is invited. They are
tracked in [`DEPLOYMENT_CHECKLIST.md`](DEPLOYMENT_CHECKLIST.md).

| Gate | Evidence | Owner |
|---|---|---|
| Production deployment is live on HTTPS with the real domain | checklist 1.6, 1.9, 3.3 | ADESA IT |
| `WEBAUTHN_RP_ID` / `WEBAUTHN_ALLOWED_ORIGINS` match the deployed domain | checklist 1.7: a real passkey registration succeeds | ADESA IT |
| Architecture is `prod` with enforcement **on** and the office allowlist configured | checklist 2.8, 2.9, 2.13 | ADESA IT |
| Resolved client address confirmed from ADESA HQ Wi-Fi and matched to the verified office egress | checklist 2.10 (authenticated diagnostics endpoint) | ADESA IT |
| Sign-in **and** sign-out refused from a mobile hotspot | checklist 2.11 | ADESA IT |
| Edge firewall / WAF restriction in place as the second layer | checklist 3.1–3.4 | ADESA IT |
| Backup taken **and a restore rehearsed** on the production database | checklist 1.8 | ADESA IT |
| Security review accepted | checklist 5.6 / `SECURITY_REVIEW.md` | ADESA IT |
| HR correction policy and working-hours rules agreed | checklist 5.1, 5.2 | ADESA HR |
| Retention period agreed | checklist 5.3 | ADESA |
| Support path published to pilot users | checklist 5.4 / runbook | ADESA IT |
| Pilot cohort named and briefed | this plan, section 4 | ADESA HR |

**No gate may be waived.** A pilot that starts before the network check is
verified would repeat the exact failure that the security review was written to
prevent.

## 4. Pilot cohort **[ADESA decision]**

Recommended shape (small enough to support, large enough to find problems):

| Group | Recommended size | Why |
|---|---|---|
| Employees | 10–20, across at least three departments and two device types (Android/iOS or laptop) | exercises different passkey/platform behaviour |
| HR officer | 1 (plus 1 backup) | the correction and reporting workflow |
| Administrators | 2 (never only one) | account/device management; the system refuses to leave itself without an active administrator |
| IT support | 1 named contact | device approval, network diagnostics, escalation |

Selection criteria: volunteers who are in the office most days, a mix of
technical comfort, and at least one person who works from a hotspot sometimes
(so the refusal path is exercised by a real user).

Records that must be kept by ADESA HR: the cohort list, the briefing date, and
each participant's consent to use a personal or company device.

## 5. Pilot timeline **[ADESA decision]**

A two-week pilot is the minimum that covers a full attendance cycle and a
weekend.

| Stage | Duration | Exit condition |
|---|---|---|
| **Dry run** | 1 day, 3–5 people | every participant can register a device, be approved, sign in and sign out |
| **Week 1** | 5 workdays | daily reconciliation passes every day; no severity-1 incident |
| **Week 2** | 5 workdays | success criteria met; support load acceptable |
| **Review** | 1 day | go / no-go decision recorded |

Before the dry run, IT performs the office-Wi-Fi and hotspot checks once more on
the final production build.

## 6. Success criteria

Measured over the two pilot weeks. **[ADESA decision]** on the exact thresholds;
the values below are the recommended baseline.

| # | Criterion | Target | Source |
|---|---|---|---|
| P1 | Pilot employees with an approved device | 100% by end of day 1 | device list |
| P2 | Attendance actions from the office network that succeed | ≥ 99% (excluding genuine device/user errors) | audit trail |
| P3 | Attendance actions **recorded** from an unapproved network | **0** | security log + audit |
| P4 | Successful attendance actions with no audit entry | **0** | audit trail reconciliation |
| P5 | Attendance records with no correction history where a correction was made | **0** | correction history |
| P6 | Daily reconciliation: records vs. HR's manual register | 100% of workdays | HR |
| P7 | Median time from opening the dashboard to a confirmed sign-in | ≤ 15 s | observation |
| P8 | Severity-1 incidents (data loss, unauthorised access, system down for a full day) | **0** | incident log |
| P9 | Support requests per employee per week | ≤ 0.5 | support log |
| P10 | Participant satisfaction (short survey) | ≥ 4/5 average | survey |

A criterion is either met or not; partial credit is not used for the go/no-go
decision. Any P3, P4 or P8 failure stops the pilot (section 9).

## 7. What the pilot measures (instrumentation)

- **Attendance reconciliation** — HR exports the PDF report for each workday and
  compares it with the manual register. Discrepancies are investigated the same
  day; a correction is recorded with a reason, never by editing the database.
- **Security log review** — IT reviews `event=attendance_denied` entries daily:
  action, reason, resolved client IP. A denied request from an address that
  should have been approved is a finding, not noise.
- **Audit trail review** — ADMIN checks that every sensitive action of the day
  is present (account, device, correction, network).
- **Support log** — every request is logged with time, employee, symptom,
  resolution and whether it was a product defect.

## 8. Roles and responsibilities

| Activity | ADESA HR | ADESA IT | Pilot employees |
|---|---|---|---|
| Cohort selection and briefing | **R** | C | I |
| Device approval | I | **R** | C |
| Daily reconciliation | **R** | C | — |
| Network and infrastructure | — | **R** | — |
| Security log review | — | **R** | — |
| Incident decisions | C | **R** | I |
| Go/no-go recommendation | **R** | **R** | — |
| Go/no-go approval | **A** (management) | C | — |

R = responsible, A = accountable, C = consulted, I = informed.

## 9. Stop / rollback criteria

Stop the pilot and revert to the previous attendance process if any of these
occurs:

- an attendance action is recorded from an unapproved network (P3);
- an attendance record is changed without a correction entry (P5);
- a security incident involving credentials, devices or the audit trail;
- the system is unavailable for more than one full workday;
- HR cannot reconcile the records for two consecutive workdays.

**Rollback is a business decision, not a data deletion.** The attendance records
already captured remain valid; the pilot is paused, the previous process
resumes, and the records are reconciled by HR. No database volume or attendance
row is deleted to "reset" the pilot (see the runbook).

## 10. Go / no-go decision

At the review, ADESA decides:

- **Go** — all criteria met; proceed to company-wide rollout planning.
- **Conditional go** — criteria met except minor ones, with a dated remediation
  plan and a short extension.
- **No-go** — one or more stop criteria fired; the findings are fixed and the
  pilot is repeated.

The decision, the evidence and any accepted residual risk are recorded in
[`DEPLOYMENT_CHECKLIST.md`](DEPLOYMENT_CHECKLIST.md) section 5.

## 11. Communication plan

| When | Audience | Message |
|---|---|---|
| T−5 days | Pilot cohort + line managers | what changes, what is expected, how to get help |
| T−1 day | Cohort | device registration instructions and the support contact |
| Day 1 | Cohort | "sign in and out today; report anything odd" |
| Weekly | Cohort + management | one-paragraph status, open issues, next step |
| Review | Management | go/no-go with evidence |

Messages must state the two rules plainly:

1. **Attendance is only recorded from the ADESA office network.** A hotspot
   attempt will be refused; that is the control working, not a fault.
2. **A lost or replaced device must be reported to IT immediately.** An
   approved device is a credential.

## 12. Open decisions for ADESA

These block the pilot and are not technical:

1. Pilot cohort and dates.
2. Working hours / late / early rules (checklist 5.1).
3. Correction authority and whether a second approver is required (5.2).
4. Retention period for attendance records and audit logs (5.3).
5. The support phone number / mailbox and the escalation hours (5.4).
6. Whether the pilot counts as the official attendance record or runs in
   parallel with the current process.
