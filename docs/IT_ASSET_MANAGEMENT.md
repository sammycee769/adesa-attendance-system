# IT Asset Management — local implementation

The owner approved production release alongside Recurring Payments on 2026-11-09. Earlier local development and test details below describe the original isolated workflows; see `MODULES_RELEASE_2026-11-09.md` for the approved rollout.

## Architecture and business decisions

The module extends the existing Spring Boot/JPA/PostgreSQL application at `/api/v1/assets`. It shares employee identities, server sessions, CSRF, password/session guards, centralized errors, React routing, and existing UI styles. No attendance network or WebAuthn logic is changed. No new runtime dependencies are added. OpenPDF is reused.

`ADMIN` and the new `IT_ADMIN` manage assets and approvals. `IT_ADMIN` has no access to existing `/admin` attendance, employee accounts, device approvals, or settings. HR can view inventory/assignments, initiate and view clearance, download clearance PDFs, and view assignment/subsidiary/clearance reports. Employees can view their personal assignment history, report faults on currently assigned assets, request replacements, and view their issues, replacements, and return notices. Backend services and scoped database queries enforce these restrictions.

Assignments always require explicit approval. A pending assignment reserves the asset; rejecting it preserves the cancelled record and releases the reservation. All asset mutations take a PostgreSQL row lock; assignments and clearance also lock the employee. Replacement fulfillment takes asset locks in stable UUID order. Partial unique indexes are the final protection against duplicate active assignments, unresolved replacements, concurrent repairs, open returns, and duplicate open clearances. JPA versions protect against stale edits.

Repair transitions are explicit. Completion requires actual cost and a working condition; it restores ASSIGNED when custody remains active. A replacement approval reserves lifecycle state but never chooses a replacement automatically. Fulfillment requires an explicit available device and verified return or administrator-authorized loss accounting. Return receipt requires inspection and closes custody atomically. Damaged returns go to UNDER_REPAIR for assessment, never AVAILABLE. Clearance approval requires no active/pending custody, unresolved reported issues, or open replacement requests. Open clearance prevents new assignments until the process is resolved.

ADMIN can formally account for a lost asset with a mandatory reason. Custody is closed as CANCELLED, pending return obligations and repairs are explicitly cancelled and audited, and the asset remains LOST. This never fabricates a verified return. Employees cannot approve or reject their own replacement requests, even if their role later permits management.

There are no asset, assignment, repair, or audit deletion endpoints. Audit rows are append-only, with database triggers rejecting UPDATE/DELETE. Foreign keys preserve historical references. Employee subsidiary affiliation is optional and editable only through the asset manager endpoint; assignment subsidiary is a permanent snapshot.

## Files added

Backend package `backend/src/main/java/com/adesa/attendance/assets/`:

- `AssetRecord.java`: shared UUID, version, timestamps, actor metadata.
- `Asset.java`, `Subsidiary.java`, `Assignment.java`, `Issue.java`, `Repair.java`, `Replacement.java`, `AssetReturn.java`, `Clearance.java`, `ClearanceItem.java`, `AssetAudit.java`: JPA persistence models.
- `AssetRepository.java`: inventory persistence and pessimistic asset locks.
- `AssetRequests.java`: validated request DTOs; actor and approval timestamps are server-owned.
- `AssetService.java`: transactional domain workflows and audit writes.
- `AssetQueries.java`: scoped read projections, paginated queries, dashboards, clearance summaries, ten reports. Read DTO field names follow SQL snake_case; the page envelope uses `content`, `totalElements`, `page`, `size`.
- `AssetController.java`: REST endpoints using the existing authenticated principal.
- `AssetReports.java`: CSV escaping/formula protection and OpenPDF generation.
- `LocalAssetDemo.java`: opt-in synthetic demo configuration, guarded against nonlocal migrations.

Other new files:

- `backend/src/main/resources/db/migration/V7__it_asset_management.sql`.
- `backend/src/main/resources/application-assets-local.yml`.
- `backend/src/test/java/com/adesa/attendance/assets/AssetWorkflowTest.java`, `LocalAssetDemoTest.java`.
- `frontend/src/api/assets.ts`.
- `frontend/src/pages/assets/AssetPage.tsx`, `AssetPage.test.tsx`.
- `scripts/assets-local-backend.sh`, `assets-local-frontend.sh`, `assets-local-smoke.mjs`, `assets-local-api-smoke.mjs`.
- This document.

## Existing files modified

- Backend `employee/EmployeeRole.java`: add IT_ADMIN.
- Backend `common/GlobalExceptionHandler.java`: concurrency conflicts return HTTP 409.
- Frontend `App.tsx`: authenticated asset routes.
- Frontend `api/types.ts`: IT_ADMIN session/employee role type.
- Frontend `api/client.ts`: allow explicit CSV downloads, preserving PDF default, cookies, timeout and error handling; `api/client.test.ts` adds CSV acceptance and unexpected-HTML rejection coverage.
- Frontend `components/Layout.tsx`: asset navigation; attendance/employee/audit navigation remains HR/ADMIN only.
- Frontend `pages/admin/EmployeesPage.tsx`: administrators can select IT_ADMIN.
- Frontend `styles.css`: responsive asset tables, modal forms, summary cards, charts, print styles.

Existing unrelated health-monitoring script and documentation changes are left intact.

## Migration

V7 creates the ten asset tables, per-type asset number sequences, employee subsidiary FK, metadata, unique indexes, search/filter indexes, enum/cost/date checks, deferred asset/custody integrity triggers and immutable audit triggers. V1–V6 are unchanged. Asset IDs are generated by PostgreSQL sequences (`ADESA-LAP-0001`, `ADESA-PHN-0001`); sequence gaps after failed transactions are expected.

Return status adds CANCELLED for formally accounted losses. OVERDUE is computed from pending return due dates at query time, so no scheduler is required. Default repair/return/clearance lifecycle values in asset details are derived from real related records.

## API inventory

All routes below are prefixed `/api/v1/assets`, authenticated, and retain existing CSRF rules for writes.

| Method | Path | Purpose |
|---|---|---|
| GET / POST | `/` | Search/page/sort inventory; register asset |
| GET / PUT | `/{id}` | Asset details and scoped history; versioned edit |
| GET | `/{id}/history` | Scoped immutable audit history |
| POST | `/{id}/status/{status}` | Retire, record unassigned loss, or dispose; mandatory reason |
| POST | `/{id}/account-loss` | ADMIN-only formal loss accounting |
| GET / POST | `/assignments` | Assignment history / submit for approval |
| POST | `/assignments/{id}/approve`, `/reject` | Complete or reject assignment |
| GET / POST | `/issues` | Scoped issues / report personal assigned asset fault |
| PATCH | `/issues/{id}` | Status, priority and troubleshooting notes |
| GET / POST | `/repairs` | Repair history / request repair |
| PATCH | `/repairs/{id}` | Review, decide, start, complete, reject, cancel |
| GET / POST | `/replacements` | Scoped requests / request personal replacement |
| POST | `/replacements/{id}/review`, `/approve`, `/reject` | Review and decide |
| POST | `/replacements/{id}/fulfil` | Explicit replacement selection after old asset accounting |
| GET / POST | `/returns` | Return notices/history / initiate return |
| POST | `/returns/{id}/complete` | Verified receipt and inspection |
| GET / POST | `/clearance` | List / initiate clearance |
| GET | `/clearance/employee/{employeeId}` | Employee clearance records |
| GET | `/clearance/{id}/summary`, `/pdf` | Printable summary / downloadable PDF |
| POST | `/clearance/{id}/approve`, `/reject` | Finalize or record NOT_CLEARED |
| GET | `/dashboard` | Database statistics, distributions, monthly repairs, recent activity |
| GET | `/reports` | Report projection selected by `report` |
| GET | `/reports/export` | `report=...&format=csv` or `pdf` |
| GET | `/audit-logs` | Search/page audit events (asset managers only) |
| GET | `/employees?search=...` | Minimal employee picker, without account credentials |
| PUT | `/employees/{id}/subsidiary` | Update current subsidiary affiliation |
| GET / POST | `/subsidiaries` | Read / register subsidiaries |

Paged reads accept `page` (zero-based), `size` (1–100), `search`, `status`; workflow reads also accept `assetId`/`employeeId` where applicable. Inventory supports `condition`, `type`, `subsidiary`, `sort=assetCode|purchaseDate|status|brand|createdAt`, `direction=asc|desc`. Search selectors query the backend, rather than loading the complete employee or asset database. Reports are authorized before export and neutralize formula-leading CSV cells.

Report keys: `INVENTORY`, `BY_EMPLOYEE`, `BY_SUBSIDIARY`, `AVAILABLE`, `DAMAGED`, `UNDER_REPAIR`, `REPAIR_COSTS`, `PENDING_REPLACEMENTS`, `OUTSTANDING_RETURNS`, `CLEARANCE`.

## Frontend pages and workflows

`/assets` chooses the authorized landing page. `/assets/:section` provides dashboard, inventory, assignments, issues, repairs, replacements, returns, clearance, reports and audit history. Navigation and controls reflect role permissions; backend enforcement remains authoritative. Shared forms include labels, validation, focus containment, Escape/cancel, busy states, confirmation notes, backend errors and success notifications. Inventory includes detail/history, edit, assignment, repair, retirement and ADMIN loss accounting. Employee/asset selectors search names/emails and asset identities; selecting an employee confirms their recorded subsidiary when available. Return initiation is available from assignments and the returns page.

## Local demonstration

Prerequisites: Java 21, Maven, Node 22–24, cached project dependencies and a local Docker daemon with `postgres:16-alpine` available. Never source existing deployment `.env` files for this demo.

Create only the dedicated loopback database:

```bash
env -u DOCKER_HOST -u DOCKER_CONTEXT -u DOCKER_TLS -u DOCKER_TLS_VERIFY -u DOCKER_CERT_PATH \
  docker --host=unix:///var/run/docker.sock run --detach --name adesa-assets-local-postgres \
  --publish 127.0.0.1:55437:5432 \
  --env POSTGRES_DB=adesa_assets_local --env POSTGRES_USER=assets_local \
  --env POSTGRES_PASSWORD=assets_local_demo_only postgres:16-alpine
```

If that container already exists, inspect it and use `docker --host=unix:///var/run/docker.sock start adesa-assets-local-postgres`; do not repoint the demo at another database.

From the repository root, build the backend locally (`cd backend && mvn -o -DskipTests package`), then run these in separate terminals:

```bash
bash scripts/assets-local-backend.sh
bash scripts/assets-local-frontend.sh
```

- Frontend: http://localhost:5174/assets
- Backend health: http://127.0.0.1:8081/actuator/health
- Backend API: http://127.0.0.1:8081/api/v1/assets
- Swagger (authenticated session): http://localhost:8081/api/v1/swagger-ui.html

The profile binds the backend to loopback and verifies the exact dedicated JDBC URL before migrations. Launch scripts clear inherited configuration and do not load deployment secrets. Mail is unconfigured; no real employee data is seeded. Attendance network and passkey enforcement are unchanged, so the synthetic demo users do not bypass attendance security.

Synthetic demo logins all use password `LocalAssets-Only-2026!`:

| Email | Role |
|---|---|
| `admin@assets.example.test` | ADMIN |
| `it_admin@assets.example.test` | IT_ADMIN |
| `hr@assets.example.test` | HR |
| `employee@assets.example.test` | EMPLOYEE |

Initial data: one synthetic subsidiary, one assigned laptop and one available phone. Use separate browser profiles for role testing.

Suggested walkthrough: register another asset → submit/approve assignment → employee reports an issue → IT creates/reviews/approves/starts/completes repair → employee requests replacement → IT approves → IT initiates and verifies old asset return → explicitly select replacement → HR initiates clearance → verify approval is blocked while custody remains → return all assets and resolve obligations → approve and download clearance PDF → inspect reports and immutable history.

## Verification commands

```bash
cd backend
env -u DOCKER_HOST -u DOCKER_CONTEXT -u DOCKER_TLS -u DOCKER_TLS_VERIFY -u DOCKER_CERT_PATH \
  DOCKER_HOST=unix:///var/run/docker.sock mvn -o -B verify
cd ../frontend
npm test -- --maxWorkers=1
npm run build
```

Backend integration tests always supply their own disposable PostgreSQL container URL. They never use the deployment `.env`. Frontend tests mock API access and use synthetic identities. One frontend worker avoids excessive CPU/memory contention on this workstation.

API smoke: `node scripts/assets-local-api-smoke.mjs` runs the complete synthetic lifecycle with all four roles against the fixed loopback backend. It returns the demo employee’s devices and completes their clearance.

Browser smoke: start an isolated headless Chrome with DevTools on loopback port 9227 and a fresh temporary profile; run `node scripts/assets-local-smoke.mjs`. It logs in locally, registers an asset through the actual UI, verifies the API record, downloads a PDF and captures a mobile screenshot at `/tmp/adesa-assets-mobile.png`.

Executed on 2026-11-05, locally:

| Check | Result |
|---|---|
| Full backend `mvn -o -B verify` | **248 passed**, zero failures/errors/skips; includes existing attendance, authentication, passkey, security and migration regressions |
| Final focused asset + local guard `verify` after subsequent asset changes | **13 passed**, zero failures/errors/skips (10 workflow tests, 3 local-database guard tests) |
| Complete frontend suite, one worker | **86 passed** across 14 files, including 10 new asset workflow tests and 2 CSV download tests |
| Frontend TypeScript + Vite build | Passed |
| Live local API lifecycle | Passed with ADMIN, IT_ADMIN, HR and EMPLOYEE sessions; assignment, issue, repair, replacement, verified returns, blocked/approved clearance, all ten reports and audit history |
| Live Chrome UI smoke | Passed: UI registration → real API record, PDF and CSV exports, 390px mobile viewport without horizontal page overflow, zero JavaScript exceptions |
| `git diff --check` | Passed |

The full backend run includes the original eight asset tests; the final focused run repeats those and adds privacy/loss/clearance checks plus local-only guard tests. These counts overlap and should not be added together. The final focused build uses the same source tree with a temporary Maven POM/output directory so it does not overwrite the running full regression suite's classes. Its verified JAR is the one used by the local demo and copied into `backend/target` for the supplied launch script.

Logs for review: `/tmp/adesa-assets-backend-verify.log`, `/tmp/adesa-assets-final-tests.log`, `/tmp/adesa-assets-frontend-final.log`, `/tmp/adesa-assets-build.log`, `/tmp/adesa-assets-api-smoke.log`, `/tmp/adesa-assets-browser-smoke.log`. Mobile screenshot: `/tmp/adesa-assets-mobile.png`.

No known functional blockers remain from the executed checks. Production data-volume/load testing and deployment rehearsal have not been performed. The local smoke scripts modify synthetic demo records; after the clearance walkthrough, the original demo employee's devices are returned. Assign another available asset to that employee to test new personal issues/replacements. Mail delivery remains disabled in the demo, and attendance still fails closed until its existing independent requirements are satisfied.

## Future deployment considerations — no authorization granted

Before any separately authorized deployment: review V7 and its permissions/retention rules; back up and rehearse on a nonproduction database; confirm real subsidiary affiliations and authorized approvers; test with representative volume; provision IT_ADMIN through an existing administrator; ensure the demo profile is never enabled; review authentication/session behavior across role changes; and coordinate backend/frontend rollout. No remote push or deployment is part of this task.

## Follow-up: employee asset requests and email notifications

Added `V8__asset_requests_and_mail_notifications.sql`; V1–V7 remain unchanged. V8 adds `asset_requests`, `asset_mail_outbox`, and repair requester ownership/backfill. The new persistence/services are `DeviceRequest`, `AssetMail`, `AssetNotifications`, `AssetMailDispatcher`, and `AssetMailScheduling`. Request DTOs, transactional services, scoped queries/controllers, repair ownership, dashboard totals, frontend types/forms/navigation, and tests are extended in the existing asset package. Test-only `application.properties` disables automatic email scheduling during integration tests; dispatcher behavior is tested separately.

Employees now land on **Asset Requests** at `/assets/requests`. The request form shows a searchable catalogue of working AVAILABLE devices with no active assignment, pending employee request, or open repair. Catalogue responses omit employee details, serial numbers, costs and history. Submitting a request reserves that device until IT reviews it. Rejection releases it. Approval atomically creates an approved assignment to the requesting employee; the requester comes from the authenticated session. Employees cannot approve requests, and asset managers cannot approve their own asset requests. IT assignment and replacement workflows cannot take a device reserved by a pending employee request. Outstanding requests also block final employee clearance.

Employees can request repairs from **Repairs & Maintenance** for their currently assigned devices. They see their own repair request status and dates without technician/cost fields or approval controls. IT retains the existing review, approval and repair lifecycle. Replacement requests retain their ownership checks and explicit IT selection/old-device-accounting requirements.

Additional API routes under `/api/v1/assets`:

- `GET /available?search=...&page=0&size=20`: minimal available-device catalogue.
- `GET /request-subsidiaries`: active subsidiaries for self-service requests.
- `GET /requests`: personal requests, or all requests for ADMIN/IT_ADMIN.
- `POST /requests`: `{assetId, subsidiaryId, reason}`.
- `POST /requests/{id}/approve` and `/reject`: IT decision with mandatory notes.
- `POST /repairs/requests`: `{assetId, description, notes}` for a personally assigned device.

Asset, repair and replacement submissions generate confirmation emails to the requester and notifications to active IT_ADMIN/ADMIN accounts. Approvals and rejections notify the same recipients. Recipients come from existing employee records; no email addresses are hardcoded. Messages contain the asset code, employee, request reference, explanation, and a link into the relevant authenticated page.

Email rows are inserted inside the request/approval transaction. Rolled-back operations produce no notification. A separate scheduled worker processes committed rows in batches with `FOR UPDATE SKIP LOCKED`, using the existing MailService transport. Unique event/request/recipient keys prevent duplicate queue entries. Delivery errors retain the message for exponential retry, up to eight attempts, then FAILED for operator inspection. SMTP/API handoff is inherently at-least-once if a process crashes after handoff but before recording success. `SENT` records transport acceptance, not a guarantee that a recipient read the message.

In the local demo the existing LoggingMailService captures emails without contacting any real mail server; outbox status is `LOCAL_CAPTURED`, not `SENT`. Read the generated messages in `/tmp/adesa-assets-backend.log` (`development mail` sections). Delivery polling defaults to five seconds and can be controlled locally with `adesa.assets.mail.enabled` and `adesa.assets.mail.poll-ms`. No production mail settings or secrets have been changed.

Additional verification:

- `AssetWorkflowTest` adds available-only requests, reservations, approval/assignment, rejection/release, concurrency, ownership, recipient checks for all three request types, duplicate approval and rollback checks.
- `AssetMailDispatcherTest` checks captured mail, HTML escaping, real transport acceptance, retry and terminal failure handling.
- Four frontend tests cover employee asset selection/submission, reservation conflict feedback, employee repair requests and IT asset-request approval.
- `node scripts/assets-local-requests-smoke.mjs` exercises the three request/approval flows through real local HTTP sessions using synthetic accounts. It leaves synthetic approved requests visible for review.
- `node scripts/assets-local-request-browser.mjs` uses the isolated local Chrome DevTools session to submit an employee request through the actual UI and verify reservation, approval-control visibility and mobile layout. Screenshot: `/tmp/adesa-employee-request-mobile.png`.

Test results for this follow-up are recorded after the final local checks below. All local launch URLs and synthetic passwords above remain the same. Production release was subsequently approved on 2026-11-09.

Follow-up verification completed locally on 2026-11-05:

| Check | Result |
|---|---|
| Focused asset workflow, mail dispatcher and local guard tests | 21 passed, zero failures/errors/skips |
| Final combined asset + attendance API/network + passkey + security regression run | 66 passed, zero failures/errors/skips; includes the 21 focused tests |
| Full frontend suite | 90 passed across 14 files |
| TypeScript and Vite build | Passed |
| Live employee HTTP walkthrough | Passed for asset, repair and replacement submissions and approvals |
| Local email delivery worker | All six submission/approval event types captured for employee, IT_ADMIN and ADMIN: 18 LOCAL_CAPTURED rows, no real deliveries |
| Real browser employee request | Passed: employee submission, reserved device removed from catalogue, no approval controls, 390px mobile layout, zero JavaScript exceptions on final run |
| Whitespace check | `git diff --check` passed |

The first browser attempt submitted successfully but its subsequent evidence fetch failed transiently; a rerun with network diagnostics passed without application changes. Both synthetic submissions remain visible as pending requests for IT review. The live API walkthrough also leaves a synthetic assigned device and approved repair/replacement examples visible. These records are exclusively in the dedicated local demo database.

Follow-up logs: `/tmp/adesa-requests-backend-tests.log`, `/tmp/adesa-requests-regression.log`, `/tmp/adesa-requests-frontend-final.log`, `/tmp/adesa-requests-frontend-build.log`, `/tmp/adesa-requests-live-api.log`, `/tmp/adesa-requests-browser.log`. Email bodies remain in `/tmp/adesa-assets-backend.log`. No known functional blockers remain from these checks; real external mail delivery was intentionally not exercised locally.
