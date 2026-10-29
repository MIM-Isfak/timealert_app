# TimeAlert project handover

Branch: `feature/timealert-student-alerts`. The shared timetable and durable reminder engine are implemented. The free public pilot release is prepared for Cloudflare Pages, Render Free, Neon Free, and an isolated Cloudflare SQLite Durable Object Worker. See Step 6 for current checks and the account-confirmation boundary; earlier sections record previous checkpoints.

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

---

## Step 6: Free public pilot release preparation

### Release changes

- Added a non-root Java 17 Docker build and Render Free Web Service Blueprint with auto-deploy disabled, the feature branch selected, and `/actuator/health`. No Render database resource is defined. Docker's build context excludes local environment files and credentials.
- Added an explicit `pilot` Spring profile: Render `PORT` binding on `0.0.0.0`, required environment-based credentials, SSL PostgreSQL URL validation for Neon, three database connections, a single exact HTTPS frontend origin, safe error responses/logging, and no SMTP health dependency. `LEGACY_EMAIL_REMINDERS_ENABLED=false` prevents the legacy email service and its scheduled database polling from being registered. The default/local profile can still opt into personal email behavior.
- Added Cloudflare Pages build instructions/configuration for Create React App (`frontend`, Node 22, output `build`), an HTTPS API URL preflight using `REACT_APP_API_URL`, SPA routing/browser headers, and source-map-free pilot builds.
- Added a live next-event countdown, batch-timezone today/current-week filters, and an authenticated `.ics` snapshot export. The export reuses approved-membership authorization, omits drafts, uses stable IDs/version sequences and UTC times, retains cancellation status, escapes calendar text, and folds Unicode lines safely. No public calendar URL, access token, or calendar alarm is introduced.
- Preserved private Worker configuration: Spring `PUSH_SERVICE_SECRET` matches Worker `INTERNAL_API_SECRET`. Added a separate undeployed `pilot` namespace configuration. The existing `test` environment pauses reminder alarms with `REMINDER_DELIVERY_ENABLED=false`; manual test pushes are not invoked during release verification.
- Main and component READMEs now describe the account/deployment sequence, Render cold starts, quota limits, internet requirements, lack of an exact-second guarantee, and provider acceptance versus confirmed device delivery.

### Verified results

- Backend: **69 tests passed, 0 failed**; Maven `package` produced the executable Spring Boot JAR. New tests cover Render/profile properties, SSL requirements, disabled legacy scheduling without SMTP, exact production CORS, calendar authentication/approval/cross-batch denial, UTC content, cancellation/version information, injection escaping, and Unicode folding.
- Frontend: **47 tests passed, 0 failed** across 11 suites; `build:pilot` production build **passed** using an execution-only illustrative HTTPS API URL. Missing and localhost API URLs were rejected. New coverage includes timezone midnight/week boundaries, next-event selection/countdown, view switching, authenticated calendar download and failed authorization.
- Worker: **24 tests passed, 0 failed**, with all outgoing push requests mocked. The added pause test verifies jobs are retained without arming/executing delivery alarms.
- Wrangler `--env test --dry-run` and `--env pilot --dry-run`: **passed**, each estimating **40.05 KiB** upload (10.12 KiB gzip). The pilot environment was not deployed.
- Gitleaks redacted staged scan: **no leaks found**. `git diff --check` and staged whitespace check: **passed**.
- No PostgreSQL smoke database or temporary application server was needed or created for this release step. No production users or real notifications were created/sent. Local Maven packaging does not verify the Docker image or a live Render/Neon connection.

### Existing test Worker update

- Existing Cloudflare OAuth authentication was available; read-only inspection confirmed all four required test Worker secret names. No secret values were read or changed.
- Updated only `timealert-notifications-test`, preserving its existing SQLite namespace/secrets, with reminder delivery paused.
- URL: `https://timealert-notifications-test.timealert-notification-service.workers.dev`.
- Deployed version: `a9092574-7c08-4b00-bc91-f49dd4c789e6`.
- Live checks: `/health` → **200**, body `{"status":"UP"}`; `/internal/public-key` with missing authentication → **401**, and invalid authentication → **401**, both body `{"status":"unauthorized"}`. These checks do not claim VAPID delivery readiness or access student data.

### Remaining account actions, in order

1. Confirm Cloudflare/Render/Neon accounts, project/service/database names and region, final Pages origin, owner bootstrap email, and release commit.
2. After approval, reserve the Pages project without publishing to establish its origin; create/select Neon Free storage and privately configure/deploy the isolated pilot Worker with its own four secrets. Do not point a new database at the test subscription namespace.
3. Deploy Render from the reviewed Blueprint with Neon SSL credentials, strong JWT secret, exact Pages origin, and the pilot Worker URL/shared secret; verify health and CORS. No SMTP configuration is needed.
4. Configure Pages with the final `REACT_APP_API_URL` and deploy the approved frontend build, then separately authorize real-user/device verification. No frontend, backend, database, or pilot Worker resource was deployed in this step.

Public pilot deployment remains blocked on those account choices, private configuration, and explicit confirmation. Live Render startup/Neon SSL, Pages origins, and real-device reminder timing remain unverified.
