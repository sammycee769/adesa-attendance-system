# Batch 0 attendance reporting

The HR and Admin Attendance Records page and its PDF and CSV downloads read the
same daily report query. Records are sorted by Lagos workday, most recent first.
The page requests seven matched working dates at a time and its cards total the
displayed days. The exports cover the entire selected period and include
overall totals, followed by a cumulative table with days present, days absent and expected Monday, Wednesday
and Friday office days for each employee in the filtered report. The expected
office-day count covers the selected dates even when filtering by status. Reports allow up to 366 calendar days and refuse more than
10,000 employee rows instead of silently truncating a report.

## Source of expected attendance

Flyway V20 adds `attendance_private.daily_roster`. It does not backfill employee
expectations or alter clock records, corrections or audit data. Batch 0 found
no authoritative employment dates, department history, holiday calendar,
work schedules, leave approvals or absence cutoffs in the current system.
Account creation and update timestamps and the active-account flag cannot
reconstruct those facts.

Use HR-approved daily roster snapshots that combine the organizational
calendar with employee schedules, verified employment dates and approved
exceptions. An authorized database operator can load those snapshots into V20
after HR reconciles the employee IDs, dates and source references. Load them
transactionally, verify counts and sample rows against HR's source, and
record the approval and source reference with the import. Validate staffing
coverage before marking any historical day complete. Load future verified
schedules in advance if they should appear in the report. Unverified dates may
remain unpopulated indefinitely.

Each snapshot holds the employee ID, workday, inclusive employment bounds,
historical subsidiary and department, arrangement, applicable clock times,
remarks and approved source reference. Use `OFFICE` with the approved absence
cutoff and shift end, `REMOTE` for a verified remote arrangement,
`APPROVED_LEAVE`, `EXCUSED_ABSENCE`, or `NOT_SCHEDULED` as applicable. Store
absolute instants using the Africa/Lagos offset when interpreting local
times. The approved lateness threshold may be omitted where unavailable.
The schema has no direct browser or Supabase Data API route. If the deployment
uses separate Flyway and application principals, grant the Spring application
login schema usage, SELECT and INSERT on the roster through the private connection.

## Classification and counts

- On Tuesdays and Thursdays, a scheduled employee without a clock-in is
  **Remote Work**. This includes an explanatory remark and does not override
  approved leave, excused absences, or non-working schedules.
- On other days, a scheduled office employee with no sign-in is **Not Yet Signed In** before
  the approved cutoff and **Absent** at or after the cutoff.
- A sign-in produces evidence of presence. An approved lateness threshold can
  set **Late**. A completed on-time clock pair displays **Signed Out**. An
  unclosed record becomes **Incomplete Attendance** at the approved shift end.
- The report distinguishes approved leave, excused absence, remote work and
  individual non-working days. An approved remote schedule without clock-in
  evidence does not count as present.
- Existing clock records remain in the report when there is no verified
  schedule. These rows carry a **Schedule unverified** remark. They never
  generate assumed absences before the weekday policy.
  An unclosed recorded workday with no known shift
  end becomes incomplete after its Lagos calendar day closes.
- The weekday policy and verified roster exceptions establish who was expected. Leave, excused absence and
  not-scheduled employees are excluded from expected count. Present includes
  recorded arrivals, including late, signed-out and incomplete records. Yet
  to sign out excludes incomplete records. Overall attendance percentage is
  the share of expected slots with a recorded sign-in.
- Days with scheduled attendance remain visible when there are no clock-ins.
  A day with solely non-working roster entries and no clock records is omitted.
  Recorded attendance is retained on any date, including unusual workdays.
- The current subsidiary is shown for recorded attendance without a roster.
  Historical subsidiary and department come from the verified daily snapshot.
- The existing correction action applies to saved clock records and requires
  the original HR or Admin authorization and audit reason. Missing records
  appear as virtual rows, and the existing correction API cannot create a
  manual clock record. Creating such a record would require a separate
  approved workflow.

## API and release

`GET /api/v1/admin/attendance/days` accepts `from`, `to`, `date`, `search`,
`employeeId`, `subsidiary`, `department`, `status`, `page` and `size`.
Subsidiary and department match their names case-insensitively. The existing
`GET /api/v1/admin/attendance/export` and `GET /api/v1/admin/attendance/export.csv`
use the same filters without pagination. The legacy list, legacy overview,
self-service endpoints and `PATCH /api/v1/admin/attendance/{id}` retain their
existing behavior and authorization.

After explicit approval of the page and exports, confirm the remote main
commit, capture a private database backup and the current Render and Vercel
deployment IDs, and push only the tested Batch 0 commit. Wait for CI. Stage
the frontend on Vercel, deploy the backend to Render, verify V20 and HR/Admin
API access, and confirm employee access is denied. Promote the staged frontend
and verify the live filters, grouped summaries, an approved correction test
and both export formats. Obtain verified roster data before reporting absences outside the effective
weekday policy period.

For rollback, restore the prior Vercel deployment and the prior Render build.
V20 is additive and can remain in the database, preserving any approved roster
data already loaded. Recheck backend health, access controls, and the old
attendance page and exports. A database restore is a separate decision for
actual data corruption and requires its own approval.

## Standard weekday policy

From 25 November 2026, the daily view and both ordinary exports automatically
include active employees with accounts already created by the workday. Verified
roster entries take precedence. With the standard policy, a missed clock-in
on Monday, Wednesday or Friday counts as absent after the Lagos workday ends.
A missed Tuesday or Thursday clock-in is remote work. Approved leave,
excused absence and non-working schedules retain their specific statuses.
A missed sign-out remains incomplete attendance.

The effective date can be configured with
`attendance.reporting.weekday-policy-from` once HR approves an earlier range.
Older dates retain verified roster entries and actual clock records. Accounts
that are now inactive need verified historical roster entries to classify
missing days. No attendance rows are inserted by a report.

The roster setup panel is off the attendance page. Authorized HR/Admin API
operations remain available for verified schedule exceptions or historical
imports.
