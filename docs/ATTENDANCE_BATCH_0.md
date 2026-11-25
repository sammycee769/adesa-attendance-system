# Batch 0 attendance reporting

The HR and Admin Attendance Records page and its PDF and CSV downloads read the
same daily report query. Records are sorted by Lagos workday, most recent first.
The page requests seven matched working dates at a time and its cards total the
displayed days. The exports cover the entire selected period and include
overall totals, followed by a cumulative table with days present and days absent
for each employee in the filtered report. Reports allow up to 366 calendar days and refuse more than
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
  generate assumed absences. An unclosed recorded workday with no known shift
  end becomes incomplete after its Lagos calendar day closes.
- The roster establishes who was expected. Leave, excused absence and
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
and both export formats. Obtain verified roster data before enabling historical
absence reporting for real staff.

For rollback, restore the prior Vercel deployment and the prior Render build.
V20 is additive and can remain in the database, preserving any approved roster
data already loaded. Recheck backend health, access controls, and the old
attendance page and exports. A database restore is a separate decision for
actual data corruption and requires its own approval.

## Importing an approved roster

Admin and HR can open **Include staff who did not clock in** on Attendance
Records. Download an all-active-staff template for a selected day, complete
the approved fields in a spreadsheet, and save it as a UTF-8 tab-separated
file. Keep the employee number and date columns as text to preserve their format. For a multiday import, add one row per employee and workday. Former
staff can be included by employee number when their approved historical
employment dates cover the selected day. Confirm the roster source before
importing. Dates and clock rules must use the formats listed in the form.

An import validates the whole file, rejects duplicate or previously verified
employee-days, and writes an audit entry. It does not generate clock records.
Verified rows appear in the grouped page and both filtered exports. Use up to
2,000 rows per import. The template uses the current active directory as a
starting list. HR verifies who was actually scheduled for the selected dates.

The two HR/Admin routes are `GET /api/v1/admin/attendance/roster/template?date=YYYY-MM-DD`
and `POST /api/v1/admin/attendance/roster`. This workflow uses V20 and requires
no additional migration.
