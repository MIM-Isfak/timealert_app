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

`INTERNAL_API_SECRET`, `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY`, `VAPID_SUBJECT`, `PUSH_SERVICE_URL`, `PUSH_SERVICE_SECRET`, `REACT_APP_API_URL`.

## Delivery status and next steps

- **Desktop background delivery**: PASSED with the TimeAlert tab closed. The browser service worker received the push event and displayed the notification banner on the desktop.
- **Phone delivery**: UNVERIFIED. Mobile browser testing and PWA home-screen push verification on phone devices have not been performed.
- **Public frontend/backend deployment**: UNVERIFIED. Production deployment of frontend and backend remains pending and unverified.
