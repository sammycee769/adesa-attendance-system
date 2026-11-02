# IP-first attendance with location fallback

The owner approved the office pin `6.6412393, 3.3711820` and a 100-metre radius
on 2026-11-02. Both attendance sign-in and sign-out use this policy:

1. An approved public IPv4 passes immediately, without requesting location.
2. An off-network request authenticated by the Vercel gateway receives HTTP 403
   with `code: LOCATION_REQUIRED`, before any attendance write or proof consumption.
3. The frontend requests one fresh, high-accuracy browser position (20-second
   timeout, no cached fix) and automatically retries the same action and proof once.
4. The backend accepts a fix only when it is complete, finite, no older than
   60 seconds, no more than 5 seconds in the future, and reports accuracy within
   50 metres. Distance from the pin plus reported accuracy must fit within the
   100-metre radius. An uncertainty estimate never enlarges the boundary.

Passkey approval, single-use session-bound proof, session validity, CSRF and
attendance business rules remain mandatory. A missing/forged gateway key cannot
activate the fallback. Authenticated native IPv6 can use location but cannot
pass the IPv4 allowlist. Permission denial, timeout, invalid/outside fixes and
the second request's errors stop the action; the app does not loop.

The production profile enables this owner-approved policy by default. Other
profiles default to IP-only. Optional Render overrides:

```dotenv
ATTENDANCE_GEOLOCATION_ENABLED=true
ATTENDANCE_OFFICE_LATITUDE=6.6412393
ATTENDANCE_OFFICE_LONGITUDE=3.3711820
ATTENDANCE_OFFICE_RADIUS_METRES=100
ATTENDANCE_LOCATION_MAX_ACCURACY_METRES=50
```

Keep network enforcement and authenticated ingress enabled, matching gateway
secrets, no trusted XFF proxies, and no development bypass. Set
`ATTENDANCE_GEOLOCATION_ENABLED=false` to return to IP-only attendance.

The existing admin network diagnostic reports **IP eligibility only**. A false
`withinApprovedNetwork` can now legitimately lead to location-based attendance.

The app requests location only during an attendance action. It does not watch
location continuously, persist the raw coordinates, or log them. Successful
attendance audits record `verificationMethod: OFFICE_IP` or `GEOLOCATION`;
failed location checks record `LOCATION_NOT_CONFIRMED` without coordinates.
Browser coordinates and accuracy are client-reported and can be spoofed: this
is the explicitly approved alternative to IP restriction, not cryptographic
proof of physical presence.

## Release and physical-device checks

Deploy the frontend first (it works with the older IP-only backend), then deploy
the backend. Verify the production main-document response does not disable
geolocation through Permissions-Policy.

Use a dedicated test account and approved passkey. Attendance tests create real
records. On approved office Wi-Fi, verify attendance succeeds without requesting
location. On mobile data while inside the office, verify one browser location
request and an automatic successful retry. Repeat for sign-out with a new proof.
Test outside 100m, denied permission, disabled phone location and poor accuracy:
none should create or close a record. Confirm a stale session is still rejected.
Test different phones and positions in the building before relying on the
geofence for all staff; a 100m circle may include adjacent buildings and roads.

Automated coverage: `GeolocationPolicyTest`, `AttendanceGeolocationTest`, the
existing network/ingress tests, and `frontend/src/api/attendance.test.ts`.
