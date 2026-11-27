# Events & Lead Management

Implementation lives in this isolated local HQ–Home checkout. The original `/home/semi/adesa-attendance` was inspected but not modified. This checkout's origin is a local repository path; do not push to it as a production remote.

Full architecture, preview, demonstration, tests, limitations and production approval checklist: [campaign handoff](../../Adesa-hq/docs/campaign.md).

Quick local start from this directory:

```bash
python3 scripts/campaign-demo.py start
```

The script starts a **dedicated** PostgreSQL 16 database on 55441, backend on 8087, HQ–Home on 5177 and website on 3000. It rejects occupied ports and excludes inherited production credentials. Java 21, Maven, Node 22–24 and installed dependencies are required. No production push or deployment is performed.

Stop with `python3 scripts/campaign-demo.py stop`. Local data lives only in ignored `.campaign-demo/`. The `campaign-local` profile refuses other database URLs and live mail before Flyway runs.

Campaign staff UI: `/events-leads`. Staff API: `/api/v1/campaign/staff`. Public gateway API: `/api/v1/campaign/public`, disabled by default and inaccessible without its server-only gateway secret. Existing session authentication and CSRF protect staff mutations. Administrator, MANAGE, REDEEM and VIEW are enforced by the backend.

Migration V21 creates only `campaign_private`. It does not modify operational tables. Do not expose that schema through a public database API. The application DB owner must own it or receive explicit private grants.

Startup repair (26 November): a normal-terminal launch exposed direct field access on a Spring class proxy, causing null database access before the demo seed completed. Campaign collaborators now obtain the database through a proxied method, and the underlying service fields are private to prevent recurrence. The proxy regression plus campaign safety/privacy/rule suite passed 15 tests. The launcher now checks the seeded event rather than only server health, detects an exited backend immediately, supports `restart`, and uses the tested webpack development server. Two launcher-readiness tests passed. Restart the local demo after this repair; no live runtime success is implied by the socket-restricted test session.
