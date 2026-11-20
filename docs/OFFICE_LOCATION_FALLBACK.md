# Office location for attendance

Both clock-in and clock-out require a fresh browser location inside the
owner-approved office geofence. The production defaults use the existing pin
and 50-metre radius. The browser requests a high-accuracy reading with a
15-second acquisition timeout and checks its timestamp, coordinates, and
accuracy before posting attendance. The app acquires location on demand and
keeps no copy for subsequent attempts.

The server requires authenticated ingress and validates the submitted reading.
It checks the coordinates, timestamp, and configured maximum accuracy.
It then uses the Haversine distance in metres and requires the distance plus
reported accuracy to fit within the office radius. Successful attendance
audits record `verificationMethod: GEOLOCATION`. Rejected fixes are audited
without recording coordinates. Session, CSRF, approved passkey, single-use
device proof, and attendance workflow checks still apply.

## Production configuration

```dotenv
ATTENDANCE_GEOLOCATION_ENABLED=true
ATTENDANCE_AUTHENTICATED_INGRESS_ENABLED=true
ATTENDANCE_OFFICE_LATITUDE=6.6412393
ATTENDANCE_OFFICE_LONGITUDE=3.3711820
ATTENDANCE_OFFICE_RADIUS_METRES=50
ATTENDANCE_LOCATION_MAX_ACCURACY_METRES=50
ATTENDANCE_NETWORK_ENFORCEMENT_ENABLED=false
ATTENDANCE_NETWORK_LOCAL_DEVELOPMENT_BYPASS=false
```

Keep the backend and Vercel ingress secrets matched and leave trusted XFF
proxies empty. With geolocation enabled, any existing office CIDR and network-enforcement
setting are ignored by attendance. Retain the previous deployment settings so
the pre-release backend artifact remains a viable rollback target.

## Release and physical-device verification

Before pushing the release commit, record the current deployment ID and
commit on each service and verify Render's effective settings against this
document. Disable Render auto-deploy and stage Vercel production builds without
assigning the production domain. A push to a connected production branch can
otherwise serve the frontend before the new endpoint exists.

Deploy the release commit on Render first. Confirm health at
`/actuator/health`, check Flyway startup logs, and verify an authenticated
same-origin GET to `/api/v1/attendance/location-policy` returns the configured
accuracy limit. The previous frontend can handle the new backend's
`LOCATION_REQUIRED` response during this interval. Promote the staged Vercel
build for the same SHA, then verify the site on its public origin. No Flyway
migration is added by this release.

For a rollback of both services, restore the previous Vercel deployment
first and confirm the production domain serves it. Then roll Render back to
the previous successful deploy. Have test users refresh and sign in again,
since open tabs can still run the newer frontend and a backend restart clears
in-memory sessions.

Use a dedicated test account with an approved passkey. On an iPhone, allow
location for the site and enable Precise Location under Settings > Privacy &
Security > Location Services > Safari Websites. Clock in and out while on
site, then repeat on a subsequent workday with permission still granted.
Repeat on mobile data. Check that turning off Precise Location shows actionable
feedback if Safari reports low accuracy. Test denied permission and a location
outside the office radius. Rejected attempts should leave attendance unchanged.
