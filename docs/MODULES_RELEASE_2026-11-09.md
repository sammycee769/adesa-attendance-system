# IT Assets and employee Recurring Payments release

Owner authorization: 2026-11-09. Release both modules, remove dummy data, provide Recurring Payments in every employee navbar, restrict each employee to their own schedules, and assign the initial Zoho/Data/ChatGPT schedules only to Christian Samuel (`christian@adesahq.com`). ADMIN can create/edit/assign schedules and manage all records. Employee access is read-only, including previews. Recurring-payment automatic email sending remains disabled and mock delivery is the default.

## Implementation

- Reuses existing server-side sessions, CSRF, password/session guards, PostgreSQL and Flyway.
- `/api/v1/recurring-payments` returns employee-scoped configurations, requests and attempts. Preview checks ownership before composing the email. ADMIN receives the management view; employees never receive the employee roster, settings signature HTML or global audit log.
- `/api/v1/admin/recurring-payments` keeps all mutation endpoints ADMIN-only, including schedule creation and assignment. Requests snapshot ownership so reassignment does not expose old employee history to the new owner. Pending work with changed ownership cannot dispatch automatically.
- All employees have the navigation item; unassigned employees see an empty state. The responsive menu is preserved.
- Initial amounts/dates and Christian's existing Zoho signature are preserved. Renewal is the 28th. Weekend adjustment and 09:00 Africa/Lagos delivery windows are unchanged.
- Production never activates `assets-local` or `payments-local`. Synthetic fixtures remain guarded local-profile code only. Demo data is not migrated or uploaded.
- V7 and V8 add asset workflows and the transactional outbox. V9 adds payment configuration/history/attempts/settings/audit and the three genuine schedules. V10 adds employee ownership and assigns initial schedules to the existing Christian account by email. V11 enables RLS and removes Data API access for new module tables; Spring's PostgreSQL owner connection remains the sole application data path.
- No mail credentials are committed or changed. Payments remain `PAYMENTS_TRANSPORT=mock` and `PAYMENTS_LIVE_APPROVED=false` by default, with database automation false.

## Backup and cleanup scope

A private PostgreSQL 17 custom backup of production public schema/data was taken and its archive directory verified before migration/cleanup. The archive is under `/home/semi/.adesa-migration/before-modules-20261109T121134Z/production-public.dump` (private, not committed).

Production initially contained no asset/payment tables. There were 25 employees, including exactly one clearly identified synthetic account: `attendance.test@adesa.invalid`, employee number `ADESA-TEST-001`, UUID `45fea8a7-ef0b-4b77-bfe9-341844586dc3`. Its attendance/device/account-token/challenge/proof records and test audit entries are the cleanup target. No real employee account, attendance, registered device or business asset is a deletion target. Cleanup is transactional with identity and foreign-reference guards and comparison of unrelated attendance before/after deletion. Final counts and rollout evidence are recorded below after verification.

## Deployment and verification

Release tests, deployment identifiers and post-release results will be recorded here once available. No production sending is enabled by this release.
