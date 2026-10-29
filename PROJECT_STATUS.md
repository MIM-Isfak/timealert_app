# TimeAlert project handover

Branch: `feature/timealert-student-alerts`. Step 2 adds a React notification settings panel and service worker, authenticated Spring push endpoints, and a Cloudflare Worker backed by a SQLite Durable Object. The Worker owns subscriptions by stable user ID; Spring bridges authenticated requests. Test pushes target one selected device. Scheduling is out of scope.

## Completed checks

- Worker tests: 13 passed; provider requests were mocked.
- Backend focused tests: 15 passed without database access.
- Frontend tests: 23 passed; frontend production build passed.
- Worker dry-run bundle passed.
- Backend source packaging passed.
- Desktop background Web Push delivery passed: a notification was successfully received after the TimeAlert tab was closed.
- No configured secrets or private values were found in tracked files.

## Verified commands

Run from each indicated directory; Maven requires a valid Windows `USERPROFILE`:

```powershell
# notification-service
npm.cmd test
npm.cmd run build

# backend
$mavenRepo = Join-Path $env:USERPROFILE '.m2\repository'
.\mvnw.cmd "-Dmaven.repo.local=$mavenRepo" test
.\mvnw.cmd "-Dmaven.repo.local=$mavenRepo" '-DskipTests' -f target/step2-build-a90f578ec73b4e14a1af8087bdc8bf29/pom.xml package

# frontend
npm.cmd test -- --watchAll=false --runInBand
npm.cmd run build
```

## Configuration names

`INTERNAL_API_SECRET`, `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY`, `VAPID_SUBJECT`, `PUSH_SERVICE_URL`, `PUSH_SERVICE_SECRET`, `REACT_APP_API_URL`, `OWNER_EMAILS`.

## Delivery status and next steps

- **Desktop background delivery**: PASSED with the TimeAlert tab closed. The browser service worker received the push event and displayed the notification banner on the desktop.
- **Phone delivery**: UNVERIFIED. Mobile browser testing and PWA home-screen push verification on phone devices have not been performed.
- **Public frontend/backend deployment**: UNVERIFIED. Production deployment of frontend and backend remains pending and unverified.

---

## Step 3: Roles / Batches / Invitation-Code / Membership Vertical Slice

### Implementation summary

- Roles: `OWNER`, `REP`, `STUDENT` enum; new registrations always forced to `STUDENT`.
- Owner bootstrap via `OWNER_EMAILS` environment variable (no real values in tracked files).
- OWNER can promote/revoke REP via `PUT /api/owner/users/{id}/role`.
- REP creates batches with secure 8-char invitation codes; batch ownership enforced.
- STUDENT submits join requests via invitation code; duplicate prevention; REP approves/rejects.
- `GET /api/auth/me` returns current user role for frontend role sync.
- Frontend role-specific sections: `OwnerRepManagement`, `RepBatchManagement`, `StudentBatchView`.
- Schema: Hibernate auto-creates `batches`, `batch_memberships` tables; `users.role` column added.

### Completed checks

- Backend focused tests: **41 passed, 0 failed** (`BatchSecurityControllerTests`: 10, `BatchServiceTests`: 16, `PushControllerTests`: 8, `PushBridgeServiceTests`: 7).
- Frontend tests: **31 passed, 0 failed** across 7 suites; production build compiled successfully.
- Runtime HTTP smoke test: **14/14 checks PASSED** against isolated `timealert_smoketest` PostgreSQL database (port 52101).
  - OWNER registered; `/auth/me` confirms `role=OWNER` via bootstrap.
  - New registration forced to `STUDENT`.
  - OWNER promoted student to `REP`.
  - REP login confirms `role=REP`; batch created with 8-char invite code.
  - STUDENT registered with `STUDENT` role.
  - Join request submitted → `PENDING`; duplicate join correctly rejected.
  - REP approved join request → `APPROVED`.
  - STUDENT sees approved membership by batch name.
  - STUDENT → `POST /batches` returns 403.
  - STUDENT → `GET /owner/users` returns 403.
  - Second REP → `GET /batches/{id}/requests` (owned by Rep1) returns 403.
  - Unauthenticated → `GET /rep/batches` returns 401.
- Schema: Hibernate auto-created `users`, `events`, `batches`, `batch_memberships` tables in isolated test DB.
- `git diff --check`: no whitespace errors; no secrets found in tracked diff.
- `.env`, `smoke-*.log`, `frontend/build` confirmed gitignored.

---

## Step 4: Shared Batch Timetable MVP

### Implementation summary

- Added separate `batch_timetable_events`, `batch_event_history`, and `batch_event_acknowledgements` entities and repositories. Batch timetable records do not use personal `events` storage.
- Added batch-scoped REP APIs for single events, weekly materialized occurrences, import preview, publish, edit, cancellation, history, and current-version acknowledgement counts. Ownership is checked for each management operation, including before import parsing.
- Student timetable APIs require STUDENT role and APPROVED membership. They return upcoming published and cancelled events; draft events remain private.
- Event types are `CLASS`, `EXAM`, `DEADLINE`, and `OTHER`. Events retain version and timestamps. Published edits and cancellations keep readable before/after history and reasons. Acknowledgements are version-bound, so earlier versions do not count after a change.
- Added a non-persisting `FileParserService.parseForBatch` path that uses the existing CSV/Excel/PDF parsers; personal import persistence remains intact. Preview saves only batch DRAFT records and returns parser skips and duplicate reasons.
- REP workspace now provides timetable creation/repeat, import preview, publish, event editing/cancellation, history, and counts. Approved student view shows published changes, cancellation information, and “Got it”.

### Verified checks

- Backend Maven tests: **48 passed, 0 failed** (including timetable authorization, weekly occurrences, draft visibility, import preview/parser isolation, publish, history/versioning, cancellation, acknowledgement invalidation, and cross-batch rejection).
- Frontend tests: **36 passed, 0 failed** across 8 suites.
- Frontend production build: **passed**.
- Notification-service tests: **13 passed, 0 failed**.
- Isolated PostgreSQL HTTP smoke test: **passed** on a temporary loopback-only PostgreSQL cluster/database. REP created a batch and draft; draft was hidden from the approved student; publish exposed it; student acknowledged; REP edit advanced to v2 with history and invalidated the old acknowledgement; REP cancellation advanced to v3 and remained visible to the student with cancellation details.
- Temporary JWT configuration and smoke accounts were generated only for the local process/test database. The isolated PostgreSQL cluster and backend process are stopped and removed after verification.

---

## Step 5: Durable batch reminder engine

### Implementation summary

- Added SQLite Durable Object tables for deterministic reminder jobs and per-device delivery state. Alarms wake for the next due job; an atomic SQLite claim prevents concurrent alarm executions from sending the same job at once. Jobs persist through Worker restarts and use bounded exponential retries, permanent-failure states, and expired-subscription cleanup for provider 404/410 responses.
- Added authenticated internal schedule, event-version replace, cancel, member/preference sync, and safe summary operations. Spring calls them through the existing server-only authenticated bridge; pending setup is retained on events, memberships, and preferences and can be retried by an owning REP.
- Added an IANA timezone to each batch (default `Asia/Colombo`), server-stored student reminder preferences, publish/approve/edit/cancel/device-registration synchronization, future-only reminder scheduling, and event-version job replacement. Existing personal events and their email reminders remain separate.
- Added same-origin batch timetable notification links and service-worker rendering for title/type, local event time, and room. UI status describes provider acceptance as unconfirmed device delivery.
- Student notification settings now include the four reminder options and separately show device notification state and timetable synchronization state. REP batch management includes timezone configuration and retry/status controls.

### Verified checks

- Backend Maven tests: **54 passed, 0 failed**.
- Frontend tests: **39 passed, 0 failed** across 9 suites; frontend production build compiled successfully.
- Notification Worker tests: **23 passed, 0 failed**. Coverage includes ordering across midnight, duplicate alarms, bounded retry/permanent failures, 404/410 cleanup, version replacement, member synchronization, and cancellation pushes; provider requests were mocked.
- Worker bundle: **Wrangler dry-run passed** (39.81 KiB total upload estimate); no deployment was performed.
- Isolated PostgreSQL HTTP smoke: **passed** for REP create/publish → approved student view/acknowledgement → REP edit/cancel. Verified draft invisibility, REP acknowledgement count, before/after history, and old-version acknowledgement invalidation. The temporary backend, PostgreSQL cluster, accounts, and data were stopped/removed afterward.

### Live verification boundary

- No Cloudflare deployment, Durable Object production migration, live push request, or production subscription test was performed. Provider acceptance is not confirmed device delivery. Live Worker alarm operation and real-device reminder timing remain to be verified after a separately authorized deployment.
