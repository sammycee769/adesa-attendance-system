# Authenticated office-network gateway on the existing Vercel project

The owner approved deploying this gateway while retaining the existing office allowlist. No Cloudflare
account is needed. `frontend/api/gateway.mjs` invokes the server-only proxy in
`frontend/server/office-gateway.mjs`. Vercel rewrites `/api/*` to that function;
SPA routes continue to serve index.html. Browser API calls must use the same
frontend origin after activation (`VITE_API_BASE_URL` empty).

## Why this is different from trusting Render's XFF

[Vercel documents](https://vercel.com/docs/headers/request-headers) that it
replaces incoming forwarded client IPs to prevent spoofing, and exposes that IP
as `x-vercel-forwarded-for`. The function uses only that platform header, never
Render's XFF or the user's proposed ADESA proof. It replaces caller-supplied
ADESA key/IP headers, then connects to the fixed Render origin over verified HTTPS.
The backend accepts the asserted address only after authenticating a private
256-bit gateway credential, then checks the existing office allowlist.

Missing or incorrect credentials, duplicate proof headers, malformed IPs, IPv6
attendance and direct-origin requests fail closed. A correctly authenticated
hotspot address is still denied by the office policy. IPv6 clients can still
reach login/admin APIs; IPv4-only attendance stays blocked. Authentication,
CSRF, passkeys, authorization and audit logging remain active.

The credential is a server-to-server bearer secret protected by TLS, not a signed
request or replay protocol. Possession allows forging assertions, so store it
only in Vercel's SERVER environment and Render's private environment. Never use a
VITE_ prefix, export it from client modules, log request headers/bodies, or reuse
any database/admin/SMTP credential. Vercel and Render remain trusted infrastructure.

## Activation gates

- Confirm ADESA's dedicated static office egress with the ISP/IT. The observed
  `105.127.8.52` is only a candidate. Do not broaden a CIDR to cover a changing ISP
  pool or approve the address as dedicated based solely on a diagnostic request.
- Approve the concrete changes and their production deployment. No production
  variables, allowlist or infrastructure were changed during this implementation.
- Keep enforcement enabled and local bypass disabled throughout.

## Private provisioning and staged rollout

Generate a random secret in a private terminal (ignored owner-only file, no
secret printed):

```sh
cd ~/adesa-attendance
python3 - <<'PY_SECRET'
import os, secrets
fd = os.open('.env.ingress', os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, 'w') as f:
    f.write('ATTENDANCE_INGRESS_SHARED_SECRET=' + secrets.token_hex(32) + '\n')
print('Private settings saved; never share the file or value in chat.')
PY_SECRET
```

1. Privately enter the value in Render as `ATTENDANCE_INGRESS_SHARED_SECRET` and
   in Vercel PRODUCTION as `INGRESS_SHARED_SECRET` (server secret, not VITE_).
   The two values must match. Do not add database or SMTP secrets to Vercel.
2. Deploy the reviewed backend with `ATTENDANCE_AUTHENTICATED_INGRESS_ENABLED=false`
   initially. Keep trusted proxies unset. Old enforcement stays active.
3. Set Vercel production `VITE_API_BASE_URL` to an empty value (or the exact
   frontend HTTPS origin), and deploy the reviewed frontend/function/routing.
   Keep APP_PUBLIC_URL, WEBAUTHN_RP_ID and allowed origins on the frontend domain.
   User sessions previously stored on Render's hostname require a new sign-in.
   Test real browser login, CSRF and passkey ceremonies via the gateway.
4. Enable `ATTENDANCE_AUTHENTICATED_INGRESS_ENABLED=true` with the EXISTING
   allowlist for gateway diagnostics. Changing `ATTENDANCE_ALLOWED_IPV4` requires
   separate office-address confirmation and approval; retain its current value
   during this deployment.
   Do not configure trusted XFF proxies in this mode; startup rejects mixed modes.
5. Open `/api/v1/admin/network/client-address` on the FRONTEND hostname, signed
   in as admin, from both office Wi-Fi and hotspot. Only office should resolve to
   an approved IPv4. Test forged XFF, x-vercel-forwarded-for, ADESA IP and key
   headers, duplicates and malformed values from outside. Repeat against direct
   Render: missing/wrong proof must never approve attendance. The old probe calls
   Render directly, so its results alone cannot verify the new gateway.
6. Verify actual attendance using an approved employee/device. Diagnostic/unit
   results are not live attendance verification. Check cookie rotation, login,
   logout, recovery and exports. No demo/test accounts are seeded by this change.
7. Save the key in an approved password manager and delete the local private file.
   Rotate both server stores if exposed. Reverting must restore fail-closed
   behavior, never turn off enforcement.

The function preserves cookies, CSRF, request bodies and query parameters. It
refuses redirects, never caches API data, strips hop headers, and handles Node
fetch response decompression. Vercel function body/duration/usage limits apply;
confirm reports fit those limits before deployment. Existing external health
checks can keep calling Render's public health endpoint directly.

## Verification commands

```sh
node frontend/server/office-gateway-check.mjs
cd backend
mvn -B -Dtest=AuthenticatedIngressTest,ClientIpResolverTest,ProductionSafetyValidatorTest,AttendanceNetworkGuardTest test
```

Local results: 62 backend tests and six proxy tests passed. These validate
application behavior; live Vercel header sanitization, cookies and office/hotspot
attendance remain to be verified after approved deployment.
