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

Production initially contained no asset/payment tables. There were 25 employees, including exactly one clearly identified synthetic account: `attendance.test@adesa.invalid`, employee number `ADESA-TEST-001`, UUID `45fea8a7-ef0b-4b77-bfe9-341844586dc3`. Its attendance/device/account-token/challenge/proof records and test audit entries were the cleanup target. No real employee account, attendance, registered device or business asset is a deletion target. Cleanup is transactional with identity and foreign-reference guards and comparison of unrelated attendance before/after deletion. Final counts and rollout evidence are recorded below.

## Deployment and verification

Release source: `7b7590d`.

- Complete backend suite: 286 passed, zero failures/errors/skips (`/tmp/adesa-modules-release-backend-final.log`).
- Final ownership-preview integration recheck: 17 passed (`/tmp/adesa-owner-final-scope.log`).
- Frontend suite: 98 passed; final payment/navigation check: 8 passed; gateway checks and TypeScript/Vite build passed.
- Staged frontend: `dpl_FpDbuUjySG84sKeNsfmbdesVRGZN`, READY, production target, no domain aliases assigned. URL: https://adesa-attendance-frontend-bv1ue8urk-samuels-projects-87b0a359.vercel.app
- Saved Render API credentials returned 401. The approved Git-triggered rollout will be checked through actual schema/API readiness rather than inferred from a push.
- Owner reported a live location rejection before any release push. Live gateway/backend health both returned 200; production branch remained `0d3f12b`. The reported message is the existing office-boundary rejection, not a location permission failure. Location policy source and 50m boundary are unchanged.
- At the owner's request, the existing test user's password was reset directly without mail and login verified for location testing. The owner confirmed location worked and explicitly requested continuing the release. Its account and test records were subsequently removed. Live release verification used a separate disposable account to avoid changing the location-test credentials.

## Verified live outcome

- Source commit `7b7590de4cbc6c29ae446a98c4300cb5c2ee4c39` pushed to main. [Release CI passed](https://github.com/sammycee769/adesa-attendance-system/actions/runs/42619729534).
- Render Git-triggered backend rollout succeeded. Production Flyway schema is V11; authenticated API checks verify the new controller, ownership scoping, assets and all seeded schedules.
- Vercel deployment `dpl_FpDbuUjySG84sKeNsfmbdesVRGZN` promoted to https://adesa-attendance-frontend.vercel.app after staged bundle verification through authenticated `vercel curl`. No deployment-protection setting was disabled.
- Live employee browser verification at 320, 390, 768 and 1024px passed: Recurring Payments and IT Asset Management present, other employees' schedules absent, admin controls absent, no page overflow, 44px mobile nav links, no browser exceptions.
- Live ADMIN API checks passed: creation/assignment management view available, initial schedules all owned by `christian@adesahq.com`; Zoho 72,842 NGN / 16th, Data 25,000 NGN / 20th, ChatGPT 20 USD / 28th with renewal on 28th. All three HTML previews contain Christian's original signature and public logo URLs.
- Recurring-payment automation is **false**, transport **mock**. No payment email or asset notification was submitted during verification. Production counts: payment requests 0, delivery attempts 0, asset mail outbox 0.
- Both `attendance.test@adesa.invalid` and the disposable `release.smoke@adesa.invalid` account have been deleted, together with their test-associated records. No local demo accounts or asset fixtures were uploaded. Production contains 24 real employee accounts and 58 real attendance records at final verification; real attendance can continue changing during normal use. Transactional comparison confirmed unrelated employee, attendance and registered-device rows were unchanged by cleanup.
- Asset inventory is empty, ready for real registration. The three genuine payment configurations remain; there are no dummy payment histories or attempts.
- Supabase security advisors report INFO-only `rls_enabled_no_policy` for 26 deliberately backend-only tables; no WARNING/ERROR findings. These tables use Spring sessions through the database owner connection, not Supabase client auth. New module tables revoke anon/authenticated Data API access and enable RLS with no public policy. [Advisory explanation](https://supabase.com/docs/guides/database/database-linter?lint=0008_rls_enabled_no_policy).
- Location policy source, office coordinates, approved IPs, 50m radius and accuracy requirements were not changed. No production environment variables or mail credentials were changed.

Evidence: `/tmp/adesa-modules-live-check.log`, `/tmp/adesa-modules-live-browser.log`, `/tmp/adesa-live-employee-{320,390,768,1024}.png`, private backup directory `cleanup-verification.txt`, `/home/semi/.adesa-migration/modules-live-verification.json`. Disposable credential files are private and refer to deleted accounts. Existing unrelated keep-alive and migration notes in the workspace were not included in this release.

Automatic real payment sending still requires separately approved transport configuration and enabling the admin automation switch.
