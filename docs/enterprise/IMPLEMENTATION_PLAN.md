# Enterprise platform local implementation plan

The revised instructions authorize sequential local implementation and separate local commits for Batches 1–7. Batch 0 is excluded at the owner's request on 27 November 2026. No push, production deployment, production migrations, environment changes or live test mail is authorized.

## Baseline

Existing Spring Boot 3 / Java 21, React / Vite and PostgreSQL / Flyway remain. Existing attendance, device binding, geolocation, assets, payments and Media Boards are retained. Campaign V21 and vendor V22 plus their frontend integration are pre-existing uncommitted work; preserve and exclude from enterprise batch commits. Existing MailService transports and secure session/CSRF pipeline are reused. Holdings, Energy and Media website checkouts are available; no separate Services or Distribution checkout has been established.

## Sequence and verification

1. Rebrand visible identity; add recruitment private schema, HR/Admin and subsidiary permissions, vacancy/application workflows and quarantined CV storage; implement public careers integration; build reusable persisted notification/SSE/preferences service and hook existing event sources. Verify access, file handling, email outbox, duplicate safety, responsive UI, builds and regressions. Commit only completed Batch 1 changes.
2. Implement reusable requests/approvals with explicit approvers, sequential/parallel stages, delegation rules, deadlines and audited transitions; verify concurrency and authorization, then commit.
3. Integrate scheduled payment requests and approval decisions while preserving existing schedules/templates; inspect Zoho capabilities before email synchronization. Ambiguous inbound approvals require manual review. Verify idempotency, transport failure handling and finance separation, then commit.
4. Extend procurement and partial payment records with approved amount caps and private evidence. Verify monetary concurrency and permissions, then commit.
5. Extend IT support with ticket assignment, maintenance and asset links. Verify scoped access and existing assignment history, then commit.
6. Extend HR workflows through the common approval engine and existing attendance correction logic; organizational policies remain configurable. Verify employee privacy and authorization, then commit.
7. Implement authorized management summaries/exports and performance review. Run consolidated builds, migration checks, security/regression/end-to-end tests, collect artifacts and prepare backup/rollback release report; commit and stop for combined release approval.

Testing must use isolated data and disabled live mail. Report actual test counts and skipped/blocked checks; do not claim browser, provider or website flows verified without execution evidence.

## Work in progress — Batch 1

User explicitly excluded Batch 0. Batch 1 is not complete or committed yet. Added V23 enterprise schema, notification centre/SSE/preferences, sound UI with atomic IndexedDB playback deduplication, scoped recruitment APIs/rules/dashboard, encrypted/quarantined CV storage and ClamAV scanner, durable email outbox, public careers gateway/form in Holdings and adapters in Energy/Media, visible rebranding, dashboard summaries and bridges to existing committed module events. Feature flags and live email default off. Existing campaign/vendor work remains uncommitted and preserved.

Evidence so far: initial four recruitment rule tests passed; initial notification UI build passed; a later full UI build passed after TypeScript/download helper fixes; three existing navigation tests passed. Real PostgreSQL standalone checks passed all 23 migrations plus recipient RLS, event uniqueness, mark-read ownership and preference validation. Additional changes after those runs require final recompilation/testing. Live loopback and browser IPC remain blocked in the managed execution environment; do not claim full HTTP/browser screenshots were executed.

Remaining before Batch 1 completion: finish independent enterprise-local demo/fixtures; complete scanner/download and notification/email tracking UX; synchronize latest careers adapters; write gateway/security/notification tests; run all targeted and regression checks after edits; generate honest artifacts and report; stage enterprise-only changes without bundling prior campaign/vendor work; create Batch 1 local commit. Then proceed to Batch 2 without approval pause. No push/deploy/production database/environment/mail actions.
