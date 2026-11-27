# Vendor Acquisition & Management — local handover

This module is implemented locally. It has not been pushed, deployed or activated for production. No live vendor emails have been sent. The reduced fields, declaration/signature wording, workflow and retention policy require management approval before activation.

## What changed

- A four-section invitation-only registration experience with progress, conditional questions, multiple categories, optional complete banking details, private document uploads, final review and confirmation.
- Typed signatures with three styles and live previews, or PNG/JPG signature upload. Declaration and separate electronic-signature consent are explicit and initially unchecked. Final signatures, exact wording, form version and original answers are immutable; requested corrections create newly signed revisions.
- System Administrator-only Vendor Management overview, filters, applications, profiles, registration links, status workflow, banking verification, notes, additional information requests, duplicate candidates and related vendor records.
- Private document downloads require both an administrator session and a short-lived, single-use administrator-bound ticket. Original or explicitly selected signed revision PDFs are audited.
- Existing email infrastructure is reused through a private outbox. Internal recipients are restricted to current active System Administrators. Demo delivery is disabled at the dispatcher, including logging transports.
- Approval and bank verification are separate. Payment eligibility requires both APPROVED and VERIFIED.

## Files and migration

Backend: `backend/src/main/java/com/adesa/attendance/vendor/` contains rules, cryptography, uploads, public/admin APIs, workflow, PDF, notifications, database safety checks and demo fixtures. `SecurityConfig.java` adds narrowly scoped vendor routes. `backend/Dockerfile` adds font runtime libraries and headless Java rendering.

Database: `backend/src/main/resources/db/migration/V22__private_vendor_management.sql` creates the isolated `vendor_private` schema, invitation/session/application/revision/document tables, audit records, private notices/outbox, download tickets and abuse limits. Signed revisions and attached documents reject updates/deletes. No existing operational tables are altered by this migration.

Frontend: `frontend/src/pages/vendor/VendorPage.tsx` and `vendor-admin.css`; administrator-only additions in `App.tsx` and `Layout.tsx`; image MIME support added to the existing authenticated download helper.

Public website source: `../Adesa-hq/components/vendor/`, `lib/vendor/gateway.mjs`, `app/api/vendor/[site]/[...path]/route.js`, `app/vendors/`, `public/vendor-assets/`, and the vendor privacy condition in `app/PageChrome.jsx`. Energy and Media contain self-contained copies of these adapters plus small `VendorChrome` layout wrappers. Workspace `../scripts/sync-vendor-websites.py` synchronises adapter files without replacing other website work.

## Access and data protection

Internal UI, routes and service methods require the existing ADMIN role. No event delegation, HR, employee or finance permissions grant vendor access. Private schema grants are revoked from public roles, sensitive tables enforce and force row-level security, and enabled modules refuse a superuser/BYPASSRLS database account.

The public gateway accepts only a small allowlist of registration actions; it cannot call administrator/download endpoints. It uses a server-only shared credential, a trusted subsidiary identity, same-origin checks and HttpOnly SameSite session cookies. Invitations use random 256-bit credentials and hashed lookup. Token fragments are exchanged and removed from the browser address; staff cookies are never forwarded. Revocation and expiry are checked again during unfinished registration.

Sensitive answers, bank details, files, signatures, private notes and email bodies are encrypted using AES-GCM with authenticated per-record context. Duplicate indexes use keyed fingerprints. Form responses are not persisted to browser local storage. Public registration pages have no navigation/footer/sitemap links, are marked noindex/nofollow and suppress normal analytics. No private files are placed in public assets.

Document uploads are chunked at 512 KB, capped at ten supporting files of 10 MB each and a 2 MB signature. Server inspection validates actual PDF/image contents and matching extensions; encrypted/active PDFs and excessive image dimensions are rejected. This is content validation, not a malware-scanning service: production may require an approved private scanning/quarantine integration.

## Start the isolated presentation

From a normal terminal:

```bash
cd /home/semi/Documents/adesa-hq/Adesa-home
python3 scripts/vendor-demo.py start
```

The script uses a separate PostgreSQL database on port 55442, backend 8088, HQ–Home 5180 and staged public website 3110. It never loads production environment files. Existing campaign/operational servers are left alone. PostgreSQL 16 binaries, Java 21, Maven, Node and installed project dependencies are required.

Open **http://localhost:5180/vendors**. Sign in with:

- Administrator: `admin@vendor.example.test`
- Denied-access accounts: `hr@vendor.example.test` and `employee@vendor.example.test`
- Password for these fictional accounts: `Vendor-Demo-Only-2026!`

Use Registration Links to copy a real invitation. A bare registration URL intentionally does not show the form. All five demo subsidiary pages run on localhost:3110; use fictional `.test` email addresses. The seed includes three fictional applications, example supporting PDF, typed signatures, an uploaded signature and a declared conflict. Banking data is fictional and initially unverified.

Presentation flow: create/copy a link, complete the four sections, upload a fictional document, explicitly consent and select/upload a signature, review and submit, then inspect the private administrator notification and profile. Export the original PDF, add verification notes, change the status, and verify banking separately. Request corrections to demonstrate a newly signed revision while the original remains available. Sign in as HR/employee to show denial.

```bash
python3 scripts/vendor-demo.py status
python3 scripts/vendor-demo.py restart
python3 scripts/vendor-demo.py stop
```

Logs and disposable demo data are under `.vendor-demo/`, excluded from Git. Do not reuse any demo passwords or encryption keys in production.

## Verification and artifacts

Verified here:

- Backend selection: 59 tests, 55 passed, four Testcontainers integration tests skipped because Docker is unavailable. A subsequent six-test run passed all PDF and actual HTTP security-chain checks, including nested PDF actions, anonymous denial, HR/employee/COO/Finance/Procurement denial for profiles/files/PDFs, administrator access and CSRF enforcement.
- Separate PostgreSQL 16 checks: all migrations through V22, forced RLS with a non-superuser owner, own-session isolation, administrator-only notes and signed-record immutability passed (`scripts/vendor-sql-check.py`).
- HQ–Home TypeScript/Vite build passed. Sixteen selected UI/navigation/event editor tests passed, including non-administrator vendor denial.
- Holdings Next.js production build passed in an isolated staged directory, preserving the running campaign server. Four website gateway tests passed; scoped website lint passed.
- The generated six-page A4 sample PDF was inspected on the first and signature pages; long answers, questions, declaration/consent, logo, footer and signature are present.

Sample: [Fictional vendor application PDF](vendor-demo/Vendor_Application_Fictional_Atlas_Engineering_VND-ENG-DEMO0001.pdf).

The managed execution environment prevents local sockets and Chromium IPC. Consequently the live multi-service demonstration, actual browser screenshots, mobile browser flow and email transport delivery have **not** been verified here. Do not treat these as passed. The standalone database checks do not replace the pending full HTTP workflow tests.

After starting the demo in a normal terminal, run the browser check/capture script:

```bash
cd /home/semi/Documents/adesa-hq/Adesa-hq
node scripts/vendor-demo-check.mjs
```

This script is hardcoded to the isolated local ports and checks demo mode before mutation. It exercises a browser registration, document upload, typed signature, submission, private PDF, administrator dashboard, HR/employee API denial, revoked link and mobile overflow. Screenshots and the browser-submitted PDF are written to `Adesa-home/docs/vendor-demo/screenshots/`. Install a supported Playwright Chromium if unavailable, or set `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` to a local Chromium executable. This script has been syntax checked but awaits execution outside the restricted environment.

## Known integration boundaries

Verified Energy and Media website repositories have adapters. No independent Services or Distribution website checkout was found; their branded forms currently have Holdings-hosted routes. Configure verified official registration URLs when those projects are supplied. Do not invent website domains. Other local projects were not modified without evidence of ADESA affiliation. No official Services logo was supplied; its header uses the ADESA Services name rather than an unrelated logo.

The dashboard currently returns at most 2,000 recent applications. Add server-side pagination before expecting higher event-independent vendor volumes. Status emails are queued locally; real delivery, DNS/provider configuration and production role membership require a controlled acceptance test. Screenshots are pending the normal-terminal browser run. Additional-information requests include free-text instructions and secure fresh document uploads rather than a configurable checklist builder.

## Retention and record history

Ordinary management uses archive/restore through status changes and cannot permanently delete signed records. Retention periods must be approved by management and legal/privacy owners. Review archived records using date and status filters at that approved interval. Any permanent disposal requires a separate explicitly authorised, auditable procedure covering signed revisions, documents, encryption keys and backups; no automatic disposal job is enabled. Draft-upload expiry/cleanup and policy-driven retention settings remain production follow-up work, not an implemented automatic retention service.

## Production release checklist

1. Obtain explicit deployment approval and management approval of reduced fields, exact consent text, workflow and retention policy.
2. Confirm actual System Administrator accounts. Test every non-admin role, direct API requests, PDF/document links and revoked sessions in staging.
3. Back up the destination database; review/apply V22 using the existing Flyway process. Use a dedicated non-superuser/NOBYPASSRLS application account; confirm FORCE RLS and private schema grants. Review any existing deployment database-role constraints before enabling.
4. Configure a new 32-byte base64 `VENDOR_ENCRYPTION_KEY` and independently generated gateway secret; secure and back up the key. Never rotate/remove the encryption key without an approved data re-encryption procedure.
5. Configure backend `vendor.enabled`, `vendor.management-approved`, `vendor.gateway-key`, `vendor.encryption-key`, `adesa.account.public-url` and verified `vendor.registration-pages`. Keep production approval disabled until accepted. Use existing property/environment binding conventions.
6. Configure each website's server-only `VENDOR_ENABLED`, `VENDOR_SITE`, `VENDOR_BACKEND_URL`, `VENDOR_GATEWAY_KEY`, `VENDOR_PUBLIC_ORIGIN`. Configure `VENDOR_TRUST_PROXY=true` only when deployment forwarding headers are trusted, to obtain per-client rate limits (the default conservatively shares a rate bucket). Use HTTPS and verified origins; deploy each self-contained adapter separately. Keep private gateway credentials out of public environment variables.
7. Confirm runtime fonts/fontconfig, private encrypted storage backup/restore, payload limits, rate limits, monitoring without sensitive payloads and an approved file scanning policy.
8. Run full Testcontainers integration tests with Docker, complete normal-terminal browser/mobile checks, inspect captured screenshots and PDF page breaks, and run existing operational regression suites. Test simultaneous submissions, consumed invitations, malformed uploads and amended signatures before activation.
9. Test confirmation/status/request/internal emails with controlled test addresses using the existing provider. `vendor.mail.enabled` and `vendor.mail.live-approved` must both be deliberately enabled, demo mode must be false and the transport must support real delivery. Ambiguous send failures stay in REVIEW for manual reconciliation to avoid duplicate mail.
10. Verify no demo fixture/profile or synthetic credentials are active. Do not migrate `.vendor-demo` records to production. Confirm forms remain absent from navigation, footer and sitemap and that analytics are suppressed.
