# Recurring Payments — local review

The owner approved production release of Recurring Payments and IT Asset Management on 2026-11-09. Release status and verification are recorded in `MODULES_RELEASE_2026-11-09.md`. Automatic sending is disabled by default; the payment transport defaults to mock, independently of attendance/account mail settings.

## Architecture and scope

The existing application uses Spring Boot 3.5.16 / Java 21, React 19 / Vite, PostgreSQL with Flyway, server sessions and CSRF protection. `/api/v1/admin/**` requires ADMIN in the security filter chain; the management controller additionally requires ADMIN through method security. The employee read controller uses the authenticated session identity and checks ownership. The frontend uses the existing authenticated route guard, shared API client and responsive Menu navigation. HR and IT_ADMIN cannot manage payments. All authenticated employees have the Recurring Payments navbar item; only their assigned configurations and history are returned. ADMIN manages all assignments.

The existing `MailService` only supports a single To recipient and a globally configured sender, so it was not changed. Payment delivery has its own `PaymentTransport`: mock by default, optional Zoho OAuth HTTPS transport using the existing secure `MailProperties.Zoho` settings. No credentials are returned through the payment endpoints. The existing IT signature and account emails are unchanged. No new third-party runtime dependencies.

## Included schedules

| Request | Amount | Scheduled day | Renewal day |
|---|---|---|---|
| Zoho Subscription Renewal | NGN 72,842 | 16 | — |
| Monthly Internet Data Subscription | NGN 25,000 | 20 | — |
| ChatGPT Plus Subscription Renewal | USD 20, estimated NGN 30,000–33,000 | 28 | 28 |

All use `christian@adesahq.com`, To `Finance Manager <finance@adesahq.com>`, CC `Oluwafemi Adeleye <femi@adesahq.com>, Mercy Agbo <payment@adesahq.com>, Blessing Gbadeyan <blessing@adesahq.com>`.

Templates reproduce the requested subjects and messages. Amount, name, estimated NGN, billing month and renewal day placeholders are substituted before HTML escaping. Body text must end with `Best regards,`; the signature is appended once. No name or department is inserted separately. Scheduled/renewal days are editable from 1–28, so every month supports the configured day.

## Dedicated local preview

These commands are for this development machine only. Do not source a deployment `.env` file.

```bash
# From the repository root; dedicated PostgreSQL, bound to loopback only.
docker --host unix:///var/run/docker.sock compose -f docker-compose.payments-local.yml up -d

# Build locally without running tests again.
env -i PATH="$PATH" HOME="$HOME" LANG=C.UTF-8 mvn -o -B -f backend/pom.xml -DskipTests package
bash scripts/payments-local-backend.sh
```

In a second terminal:

```bash
bash scripts/payments-local-frontend.sh
```

Open http://localhost:5176 and log in with the synthetic local account:

- Email: `admin@payments.example.test`
- Password: `LocalPayments-Only-2026!`

For an employee-role view, use `employee@payments.example.test` with the same local password. This account sees only its assigned payments (none by default), without management controls.

These fixtures exist only under the guarded `payments-local` profile and are never seeded into normal or production profiles. Before Flyway connects, the profile rejects any database URL except `jdbc:postgresql://127.0.0.1:55439/adesa_payments_local`, rejects `prod`, and requires mock delivery with live approval false. The launch scripts use a clean environment and disable account/asset delivery. Attendance network controls are unchanged.

Choose **Recurring Payments**. ADMIN can use **Add recurring payment** or **Edit → Assigned employee**. New schedules start disabled. The initial three production schedules are assigned by email to the existing Christian Samuel account (`christian@adesahq.com`), never to a newly seeded employee. Unassigned schedules cannot dispatch.

 Select a billing month and choose **Preview email** for each of the three cards. Preview does not create a delivery job. **Record preview** stores a uniquely identified monthly PREVIEW record without sending; if automation is explicitly enabled later, it can proceed on the due date or through explicit recovery. The iframe uses an empty sandbox and no-referrer. You can test enabled automation locally: every delivery is captured as `MOCK_CAPTURED`, not `SENT`.

Saved mock captures will not be resent automatically if a live provider is enabled in a future release. This intentionally prevents local test records from becoming real payment requests.

Stop the two application terminals with Ctrl+C. Stop the dedicated database with `docker --host unix:///var/run/docker.sock compose -f docker-compose.payments-local.yml stop`. The named local volume preserves review data.

## Christian's existing signature

The signature associated with `christian@adesahq.com` was retrieved read-only through Zoho’s account/signature APIs at the user's explicit request: **Adesa HQ New Email Sig**. Its exact original HTML, Christian Samuel / IT Officer identity, contact details, styling and confidentiality notice are saved in `backend/src/main/resources/mail/christian-signature.html`. No mailbox settings were modified and no mail was sent.

Only its two private Zoho image URLs were replaced with `{{PUBLIC_URL}}/assets/email/adesa-hq.png` and `{{PUBLIC_URL}}/assets/email/group-headquarters.png`. Zoho's image endpoint redirected to authentication; the original ADESA logo images already supplied by the user and used by the application are reused byte-for-byte (verified SHA-256 against the supplied Downloads files). No images were regenerated or redesigned. `PUBLIC_URL` is resolved from the existing server-side account public URL with HTML escaping, allowing loopback preview and HTTPS production images after an approved future release.

The original signature is loaded automatically. ADMIN can override the HTML in Automation and signature; clearing the override restores the imported original. Public HTTPS image URLs and the two built-in logo placeholders are supported. Relative/private Zoho URLs and active content are rejected. The preview is sandboxed. Recipients' email clients may independently block external images.

The renderer appends the effective original/override HTML once after the body. Save edits before previewing. The live transport independently refuses messages without a signature. The association was retrieved using [Zoho's read-only signature API](https://www.zoho.com/mail/help/api/get-user-signature.html); credentials and raw private image links are not committed.

## Calendar and downtime policy

`PaymentCalendar` constructs the configured date with `YearMonth`, subtracts one day for Saturday or two for Sunday, and combines it with 09:00 in `Africa/Lagos` (08:00 UTC). The calculation is repeated dynamically for every billing month. Month/year boundaries and leap years are covered; next-month dates whose adjusted Friday is in the current month are considered by the scheduler.

The scheduler polls every 60 seconds. It may send from 09:00 through the end of the adjusted sending day. It does not backfill expired delivery windows. Once a request's day ends, pending/retry records become MISSED. When restarted later in the same billing month, due dates in the past become visible MISSED records rather than being sent. Historical requests that were never generated during an entire month of downtime can be recorded using **Record preview** for that month; outdated billing months cannot be queued.

Recovery queues a confirmed-unsent request for the current Lagos billing month (also allowing next-month requests whose preceding Friday is due in the current month), with an explicit reason, fresh saved configuration snapshot, and a deadline at the next Lagos midnight. Global automation and the individual schedule must be enabled. Uncertain requests are not eligible until reconciled against provider evidence. Disabling automation or a schedule stops new claims; it cannot recall an already submitted/in-flight message.

## Durable delivery and retries

- Unique `(config_id,billing_month)` constraint prevents duplicate monthly records, including concurrent generation.
- Database transactions serialize automation decisions; row locks and `SKIP LOCKED` claim one eligible request.
- SENDING state and attempt are committed **before** network I/O. Provider calls are outside the transaction.
- Successful Zoho acceptance stores its `data.messageId`; it means provider acceptance, not proof of recipient reading or final inbox delivery. Mock captures store mock IDs and never count as successful real sends.
- Confirmed pre-submission OAuth failures and HTTP 429 rejection permit up to three attempts with 2-minute then 4-minute backoff, bounded by the day's delivery deadline. Other confirmed HTTP 4xx rejections fail without automatic retry.
- Provider timeouts, ambiguous responses, missing receipt IDs and HTTP 5xx outcomes become UNKNOWN, never automatically retried. Exception bodies and credentials are not logged.
- SENDING older than ten minutes becomes UNKNOWN on restart/recovery. A stale worker cannot overwrite an already recovered state.
- An ADMIN must check Zoho, then record accepted-with-receipt or verified non-acceptance with a reason. Only verified non-acceptance becomes eligible for a separate retry action. This is deliberately conservative because the provider cannot provide exactly-once submission.
- Configuration edits use optimistic version checks. Audit records include configuration updates, settings changes, generation, claims, results, recovery reasons and reconciliation decisions. The UI shows the latest 200 requests and 300 attempts/audit events.

## Future email environment configuration — do not enable yet

| Variable | Default / purpose |
|---|---|
| `PAYMENTS_TRANSPORT` | `mock`; `zoho` selects the optional live transport |
| `PAYMENTS_LIVE_APPROVED` | `false`; required separately for live transport |
| `PAYMENTS_POLL_MS` | `60000` |
| `ZOHO_DATA_CENTER` | Existing Zoho data-center enum, e.g. `US`, `EU` |
| `ZOHO_MAIL_ACCOUNT_ID` | Existing authorized account ID |
| `ZOHO_CLIENT_ID` | Existing OAuth client ID |
| `ZOHO_CLIENT_SECRET` | Existing secret, server-side only |
| `ZOHO_REFRESH_TOKEN` | Existing refresh token, server-side only |

Reuse the existing secure credential mechanism. The OAuth account must be explicitly authorized to send as `christian@adesahq.com`; its existing ability to send as IT does not establish that authorization. The sender is fixed server-side. The API requires the `ZohoMail.messages.CREATE` scope (or existing compatible ALL scope), per [Zoho's send API](https://www.zoho.com/mail/help/api/post-send-an-email.html). Provider receipt parsing follows [Zoho's documented response](https://www.zoho.com/mail/help/api/getting-started-with-api.html). No SMTP password or token is embedded in the module. Setting the transport does not enable database automation; that requires the ADMIN switch as a separate step.

## Files

Created:

- `backend/src/main/java/com/adesa/attendance/payments/`: calendar, composer, validated input, service, dispatcher, scheduler, controller, transport interface/configuration, guarded local demo.
- `backend/src/main/resources/db/migration/V9__recurring_payments.sql`: payment configs, automation/signature settings, monthly requests/history, attempts, audit; additive and idempotent seeds.
- `backend/src/main/resources/application-payments-local.yml` and `mail/christian-signature.html` (imported Zoho HTML).
- `backend/src/test/java/com/adesa/attendance/payments/`: calendar, workflow/authorization/database/retry tests, transport tests, local guard tests.
- `frontend/src/api/payments.ts`, `frontend/src/pages/payments/PaymentEditor.tsx`, `RecurringPaymentsPage.tsx`, `RecurringPaymentsPage.test.tsx`.
- `docker-compose.payments-local.yml`, `scripts/payments-local-backend.sh`, `scripts/payments-local-frontend.sh`, `scripts/payments-local-browser.mjs`, this report.

Modified for this feature:

- `backend/src/main/resources/application.yml`: safe payment transport defaults.
- `frontend/src/App.tsx`: ADMIN-only route.
- `frontend/src/components/Layout.tsx` and `Layout.test.tsx`: ADMIN-only responsive navigation and checks.
- `frontend/src/styles.css`: scoped responsive cards/forms, locally scrollable history tables, 44px controls.

Existing unrelated asset, account, database migration and Render keep-alive work is preserved. Release approval supersedes the earlier local-only restriction; see the release report for actual rollout status. Recurring-payment live delivery remains disabled.

## Verification and release review

Tests use synthetic users, temporary local PostgreSQL and mock REST/email transports only.

- Complete backend `mvn verify`: **278 tests passed**, zero failures/errors/skips, including attendance, device/authentication, assets and initial payment coverage.
- Final focused payment verification after signature import and boundary/validation additions: **23 tests passed**, zero failures/errors/skips. Covers all configured values/recipients/messages, weekend/month/year calculations, unique constraints, concurrency, restart uncertainty, bounded retries, expiry/recovery, sender/CC payloads/provider receipts through mock REST, HTML escaping, authentic signature rendering, ADMIN authorization/CSRF, local profile guards and disabled defaults.
- Complete frontend suite: **96 tests passed**; final payment/navigation recheck: **6 tests passed**. TypeScript/Vite build passed.
- Actual local browser → Vite proxy → Spring session/CSRF API → dedicated PostgreSQL check passed. All three emails previewed with Christian's original signature and both original images decoded successfully. ChatGPT rendered USD 20, 28th and NGN 30,000–33,000 correctly.
- Responsive browser checks at **320, 390, 768, 1024 and 1440px** passed with no horizontal page overflow; mobile nav links met 44px targets; no browser exceptions.
- Local review database after browser verification: **automation false**, **transport mock**, **three PREVIEW requests**, **zero delivery attempts**.
- `git diff --check` passed. Branch HEAD remains `0d3f12b`; no commits/pushes/deployments for this feature.

Evidence on this machine: `/tmp/adesa-payments-full-backend.log`, `/tmp/adesa-payments-final-backend.log`, `/tmp/adesa-payments-frontend-tests.log`, `/tmp/adesa-payments-final-frontend-tests.log`, `/tmp/adesa-payments-final-frontend-build.log`, `/tmp/adesa-payments-browser-check.log`. Browser screenshots: `/tmp/adesa-payments-{320,390,768,1024,1440}.png`. Standalone rendered email previews: `/tmp/adesa-payment-previews/{zoho,data,chatgpt}.html` (images use the local frontend while reviewing).

Outstanding: no known failing local checks. Real Zoho delivery and recipient inbox behavior remain intentionally untested, as real emails are prohibited. The existing OAuth account identifies Christian's sending identity and signature, but sender permission/scopes and live receipt behavior must be confirmed before a separately approved release. Historical billing months cannot be sent through automatic backfill; see the documented recovery window.

Before any production release: obtain explicit approval; review the local implementation and additive migration including its dependency on the existing local V7/V8 asset migrations; review the imported authentic signature and logo rendering; verify sender authorization/scopes; review environment and database backup/migration plan; deploy only after separate approval with automation still disabled; perform an explicitly authorized delivery check; then separately enable live transport and the desired schedules. No production step has been performed.
