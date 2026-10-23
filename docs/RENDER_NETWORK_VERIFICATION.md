# Render client-IP verification: enforcement stays fail closed

## Observed production requests (owner supplied, 23 October 2026)

| Connection | Direct/resolved peer | Untrusted X-Forwarded-For |
| --- | --- | --- |
| Office Wi-Fi | ::1 | 105.127.8.52, 172.71.190.248, 10.27.74.163 |
| Personal | ::1 | 129.205.124.218, 172.71.241.172, 10.28.123.5 |

Both requests had enforcement enabled, zero trusted proxies and an unapproved
resolved address. The configured office CIDR is still 105.127.6.174/32. Neither
header observation establishes an authentic client address. The office ISP/router
egress needs independent confirmation, including whether it is static and whether
the router uses multiple WANs, carrier NAT, or a VPN.

## Findings from code and platform documentation

The resolver ignores headers from untrusted direct peers. When a peer is trusted,
it walks the entire XFF chain from right to left, stopping at the first untrusted
hop. It combines repeated header lines and rejects malformed chains. Production
currently accepts only explicit IPv4 /32 trusted proxy hosts, so ::1 cannot be
configured under the current validator. Merely adding IPv6 loopback support would
still leave the changing internal and Cloudflare hops to establish and trust.
Do not add broad private networks, Cloudflare ranges, a hop count, or a leftmost
header shortcut just to make these samples resolve to the desired office address.

[Render's current article](https://render.com/articles/how-render-handles-ddos-attacks)
describes Cloudflare and Render load balancers, and recommends XFF for client-IP
handling. It does not establish stable peer addresses, a guaranteed chain length,
or sanitization of client-supplied headers for security authorization. An older
[Render feedback discussion](https://feedback.render.com/features/p/send-the-correct-x-forwarded-for)
contains conflicting observations about prepended values; it is not a sufficient
production security contract.

[Cloudflare's header reference](https://developers.cloudflare.com/fundamentals/reference/http-headers/)
describes appended XFF values, Worker-specific behavior and spoofing caveats for
True-Client-IP in stacked CDN paths. Header names alone do not establish trust.
No guarantee was found in the reviewed Render documentation that every access
path overwrites True-Client-IP/CF-Connecting-IP. This is an unresolved platform
assurance question, not proof that those headers are always spoofable.

## Run authenticated request probes privately

On the computer attached to each network, run:

```sh
cd ~/adesa-attendance
python3 scripts/check_office_network.py
```

Use the current admin password at the hidden prompt, never in command arguments
or chat. This creates an audited login/session, reads ADMIN-only diagnostics, then
attempts logout. It does not record attendance or change security configuration.
It keeps passwords/cookies in process memory only, verifies TLS, refuses redirects
and suppresses provider/error bodies. Run once on office Wi-Fi with VPN/mobile data
off, then once on a personal connection/hotspot. Share the diagnostic JSON, not
passwords, cookies or browser request exports.

Cases include baseline, both office candidate IPs, an entire forged chain, repeated
header lines, malformed XFF and alternative forwarding headers. A result resolving
to ::1 while trust is empty confirms only the existing fail-closed behavior. The
current diagnostic exposes XFF, not True-Client-IP/CF-Connecting-IP, so it cannot
show whether those alternative headers were overwritten. Edge rejection of a
request is not an authenticated backend diagnostic result. These are diagnostic
requests, not completed attendance acceptance tests.

Before relying on a forwarded address, obtain Render's supported guarantees about
direct peers, overwrite/append behavior (including duplicates), origin bypass,
custom domains and platform changes. Repeat outside-office forgery checks against
the final configuration and verify real attendance separately using an approved
test employee/device. Do not loosen production enforcement to run the probes.

## Secure alternatives if Render cannot supply the required guarantees

Use an office-controlled gateway with a stable egress IP and an ingress that you
control: enforce the source network at that ingress, remove caller-supplied IP
headers, and authenticate its backend requests with mTLS or a short-lived signed
assertion that the backend validates. The direct Render origin must reject
attendance requests that lack this authenticated ingress evidence; hiding the
origin URL or a CORS rule is insufficient. Scope the restriction to attendance
so administration and recovery remain available as intended. This requires a
reviewed implementation and separate deployment approval.

Alternatively host the backend behind a controlled load balancer/proxy with
documented source-IP preservation and restrict direct origin access. Confirm
hosting costs and the office's static-IP capability before choosing infrastructure.
Do not broaden the office CIDR to cover a provider's changing public-IP pool.

## Prepared implementation

The authenticated ingress option and gateway on the existing Vercel project are
prepared locally; see [gateway rollout](../gateway/README.md). Vercel documents
client-IP overwriting, avoiding the need to establish Render's internal proxy
chain. No production settings or office allowlist have been changed. The proposed
address still requires confirmation and live gateway acceptance testing.
