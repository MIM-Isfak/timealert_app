# TimeAlert Web Push foundation

This service owns browser device subscriptions in a **SQLite-backed Durable Object**.
React talks only to Spring Boot; Spring authenticates the JWT, resolves the database
user's stable `users.id`, and calls the Worker with a server-only credential.
SQLite reminder jobs and per-device acceptance state are persisted here. Durable
Object alarms wake for the next due job independently of Spring and the page.
Spring synchronizes event versions, approved membership and reminder preferences.

The pinned `@block65/webcrypto-web-push` **2.0.0** library handles RFC 8291
`aes128gcm` encryption and RFC 8292 VAPID using Web Crypto. Version 1.x uses an old
format that Apple does not accept. Node 22+ is required for local tooling.
Worker and Miniflare use compatibility date `2026-07-30`; the installed test
runtime supports through `2026-08-06`, so using today's later date would fail.

## Configuration

| Where | Name | Purpose |
| --- | --- | --- |
| Worker only | `INTERNAL_API_SECRET` | Random server-to-server credential (at least 32 characters) |
| Worker only | `VAPID_PRIVATE_KEY` | Private P-256 key; never send to React |
| Worker | `VAPID_PUBLIC_KEY` | Public P-256 application server key |
| Worker | `VAPID_SUBJECT` | Contact URI such as `mailto:developer@example.com` |
| Spring only | `PUSH_SERVICE_URL` | Worker origin; HTTP allowed only for local loopback |
| Spring only | `PUSH_SERVICE_SECRET` | Same credential as Worker's `INTERNAL_API_SECRET` |
| React | `REACT_APP_API_URL` | Spring API URL, never Worker credentials |

Unset bridge configuration produces **setup pending**, leaving timetable features
available. `.dev.vars`, `.env` files, `node_modules`, `.wrangler`, and generated
`dist` are ignored. Example files contain placeholders only. Use separate Worker
namespaces, credentials, and VAPID keys for development and production; numeric
user IDs are scoped to one TimeAlert database and must not share a namespace.

## Local Windows PowerShell

Use separate terminals. Check that the example ports are free first; choose other
ports consistently if needed. These examples use 8081/3001 to avoid earlier local
smoke-test services. They do not change permanent environment variables or `.env`.
Do not run these commands against a production database.

1. Start the local Worker (no Cloudflare login/deployment required):

```powershell
Set-Location 'C:\Users\HP\Desktop\Fun Projects\time-alert-app\notification-service'
npm.cmd ci
npm.cmd run setup:local
npm.cmd run dev
```

`setup:local` generates local VAPID keys and an internal credential directly into
the ignored `.dev.vars`, prints no values, and refuses to overwrite an existing
file. Existing valid credentials can be reused. Keep these keys stable between
restarts: replacing VAPID keys requires devices to subscribe again. Local Wrangler
state persists across restarts in `.wrangler/state`.

2. Build/start Spring with **existing local development database** credentials.
The following reads values privately and overrides only the launched process.
The database `timealert_dev` must already have `users` and `events`; `validate`
will not create or change its schema. SMTP credentials are cleared.

```powershell
Set-Location 'C:\Users\HP\Desktop\Fun Projects\time-alert-app'
function Read-LocalConfig([string]$Path) {
    $values = @{}
    Get-Content -LiteralPath $Path | ForEach-Object {
        if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$') {
            $values[$matches[1]] = $matches[2].Trim().Trim('"').Trim("'")
        }
    }
    return $values
}
$backendConfig = Read-LocalConfig 'backend/.env'
$pushConfig = Read-LocalConfig 'notification-service/.dev.vars'
# Never echo these dictionaries or SPRING_APPLICATION_JSON.
$env:SPRING_APPLICATION_JSON = @{
    'spring.datasource.url' = 'jdbc:postgresql://localhost:5432/timealert_dev'
    'spring.datasource.username' = $backendConfig.DB_USERNAME
    'spring.datasource.password' = $backendConfig.DB_PASSWORD
    'spring.jpa.hibernate.ddl-auto' = 'validate'
    'spring.mail.username' = ''
    'spring.mail.password' = ''
    'management.health.mail.enabled' = $false
    'legacy.email-reminders.enabled' = $false
    'jwt.secret' = $backendConfig.JWT_SECRET
    'server.address' = '127.0.0.1'
    'server.port' = 8081
    'allowed.origins' = 'http://localhost:3001'
    'push.service-url' = 'http://127.0.0.1:8787'
    'push.service-secret' = $pushConfig.INTERNAL_API_SECRET
} | ConvertTo-Json -Compress
Set-Location backend
$mavenRepo = Join-Path $env:USERPROFILE '.m2\repository'
.\mvnw.cmd "-Dmaven.repo.local=$mavenRepo" '-DskipTests' package
if ($LASTEXITCODE -ne 0) { throw 'Build failed; do not launch a stale JAR.' }
try {
    java -jar target/timealert-0.0.1-SNAPSHOT.jar
} finally {
    Remove-Item Env:SPRING_APPLICATION_JSON -ErrorAction SilentlyContinue
    $backendConfig.Clear()
    $pushConfig.Clear()
}
```

3. Start React:

```powershell
Set-Location 'C:\Users\HP\Desktop\Fun Projects\time-alert-app\frontend'
$env:REACT_APP_API_URL = 'http://localhost:8081/api'
$env:PORT = '3001'
$env:BROWSER = 'none'
npm.cmd start
```

Open `http://localhost:3001`, sign in with a personal development account, and use
**Device notifications**. Permission is requested only by **Enable notifications**.
**Test notification** targets the current device, never all devices. Disable
unsubscribes locally and attempts server cleanup. Explicit logout does the same,
even if the service is unavailable. If local unsubscription fails, retry or block
notifications in browser settings. Closing a tab does not log out or unsubscribe.
Closing each service terminal with Ctrl+C stops that terminal's process only.
Windows may lock a JAR already used by an older backend. In that case, do not stop
an unrelated instance or treat the failed output as a new build. Package an
identical copy of `pom.xml` and `src` under a fresh ignored `backend/target`
subdirectory and run that copy's POM with `-f`; use the resulting JAR explicitly.

## Isolated Cloudflare test Worker

The `test` environment deploys a separate Worker named
`timealert-notifications-test` on the account's `workers.dev` subdomain, with its
own SQLite Durable Object namespace. It does not configure a custom domain or
paid resources. Prerequisites: Node.js 22+, installed package dependencies, a
Cloudflare account with Workers/Workers.dev available, and Wrangler login. The
test environment must receive its own credentials; do not reuse production
credentials or a production Durable Object namespace. Each `secret put` prompts
for a value without placing it in the command line.

```powershell
Set-Location 'C:\Users\HP\Desktop\Fun Projects\time-alert-app\notification-service'
npm.cmd exec -- wrangler login
npm.cmd exec -- wrangler secret put INTERNAL_API_SECRET --env test
npm.cmd exec -- wrangler secret put VAPID_PUBLIC_KEY --env test
npm.cmd exec -- wrangler secret put VAPID_PRIVATE_KEY --env test
npm.cmd exec -- wrangler secret put VAPID_SUBJECT --env test
npm.cmd exec -- wrangler deploy --env test
```

After deployment, configure Spring's `PUSH_SERVICE_URL` to the returned test
Worker URL and `PUSH_SERVICE_SECRET` to the same test `INTERNAL_API_SECRET`.
Validate config locally without publishing using:

```powershell
npm.cmd exec -- wrangler deploy --env test --dry-run --outdir dist-test
```

## Contract and boundaries

All Spring `/api/push/*` routes require an authenticated, non-demo account:

| Method | Path | Result |
| --- | --- | --- |
| GET | `/api/push/public-key` | Ready with public key, or 503 setup pending/unavailable |
| POST | `/api/push/subscriptions` | Browser `PushSubscription.toJSON()`; idempotent registration |
| GET | `/api/push/subscriptions/{deviceId}` | Verify current user's device |
| DELETE | `/api/push/subscriptions/{deviceId}` | Idempotently remove current user's device |
| POST | `/api/push/subscriptions/{deviceId}/test` | Fixed test payload for this user's selected device |

Internal Worker routes require `Authorization: Bearer <server credential>`:
`GET /internal/public-key`, `POST /internal/subscriptions` (`userId`, `subscription`),
`POST` or `DELETE /internal/device` (`userId`, `deviceId`), and `POST /internal/test`
(`userId`, `deviceId`). The browser never chooses `userId` or sees this credential.
`GET /health` is public and returns only `{"status":"UP"}`; it does not claim VAPID
setup or device delivery readiness. Do not enable request/body/header logging.

The `test` environment sets `REMINDER_DELIVERY_ENABLED=false`: jobs remain pending
and alarms are disarmed so deployment checks do not release queued reminders.
Manual test pushes remain a separate operation; do not call them during release
verification. Resuming reminders requires an authorized change to `true`, deployment
and resynchronization. The prepared `pilot` environment has its own namespace with
alarms enabled. Provision its own four secrets after account confirmation; never
mix subscriptions/user IDs between test databases and Neon. See the
[pilot checklist](../README.md).

The allowlist accepts HTTPS endpoints from `fcm.googleapis.com` (`/fcm/send/` and
`/wp/`), `updates.push.services.mozilla.com` (`/wpush/`), and Apple's documented
`*.push.apple.com`. Credentials, query strings, ports, fragments, unknown providers,
and lookalike hosts are rejected. Provider redirects are never followed. Other
browser push providers require a reviewed allowlist change before support.
Requests are bounded to 8 KiB, subscriptions are limited to 10 per account, and
test sends to 3 per minute per account (both Spring and persistent Worker limits).
The single Durable Object enforces unique endpoint ownership across users.

Spring uses a 2-second connection and 7-second read timeout; Worker sends time out
after 5 seconds. The UI has a bounded network timeout and may report uncertainty
even if a slow send was accepted. Nothing automatically retries a test send.
HTTP **202 accepted means push-service acceptance, never confirmed delivery**.
HTTP 404/410 from a push provider deletes the subscription and returns expired;
other failures retain it. Unknown/wrong-owner device IDs cannot be sent to or
inspected. Removing them is an idempotent no-op, without revealing ownership.

The service worker always uses `showNotification` in foreground and background;
React does not emit a second toast for incoming pushes. Notification clicks open
the validated same-origin timetable URL or app root. It has no API fetch/cache handler and stores no
JWTs. Only a device ID/account label is stored by the page for local selection.

## Validation and hosted phone test

```powershell
# notification-service: actual Workers runtime + in-memory SQLite; outbound fetch mocked
npm.cmd test
# Local bundling only. Despite the Wrangler command name, this does not deploy.
npm.cmd run build

# backend, in its directory: mocked MVC + loopback HTTP server, no real DB/SMTP
$mavenRepo = Join-Path $env:USERPROFILE '.m2\repository'
.\mvnw.cmd "-Dmaven.repo.local=$mavenRepo" test
.\mvnw.cmd "-Dmaven.repo.local=$mavenRepo" '-DskipTests' package

# frontend, in its directory
npm.cmd test -- --watchAll=false --runInBand
npm.cmd run build
```

Deployed Web Push requires **HTTPS**, browser/device permission, correctly paired
VAPID keys, and OS/browser support. On iPhone/iPad, install the HTTPS app on the
Home Screen and open it there; the UI uses Push/Notifications/Service Worker and
standalone-display capability checks rather than user-agent sniffing. Browser
and OS policies may delay/suppress notifications. Local mocked tests and localhost
checks do not prove phone background delivery. Scheduling is persisted in SQLite;
provider acceptance is not device delivery and there is no exact-second guarantee.

Release verification may update the existing test Worker after checking account
authentication and existing secret names, then probe health and unauthorized
access only. Build/test commands do not deploy. Real phone verification requires
separate approval: use the isolated pilot environment, enable notifications, close
the app, and record notification arrival/click behavior. No health or bundling
check confirms device delivery.

## Official references consulted

- [Cloudflare SQLite storage](https://developers.cloudflare.com/durable-objects/api/sqlite-storage-api/)
- [Cloudflare SQLite migrations](https://developers.cloudflare.com/changelog/post/2026-07-09-restrict-new-kv-backed-namespaces/)
- [Cloudflare Miniflare mock fetch](https://developers.cloudflare.com/workers/testing/miniflare/core/standards/)
- [Library API and 2.x migration](https://github.com/block65/webcrypto-web-push/blob/master/packages/web-push/README.md)
- [MDN Push subscription](https://developer.mozilla.org/en-US/docs/Web/API/PushManager/subscribe)
- [MDN persistent notifications](https://developer.mozilla.org/en-US/docs/Web/API/ServiceWorkerRegistration/showNotification)
- [MDN notification clicks](https://developer.mozilla.org/en-US/docs/Web/API/ServiceWorkerGlobalScope/notificationclick_event)
- [Apple Web Push and Home Screen requirements](https://webkit.org/blog/13878/web-push-for-web-apps-on-ios-and-ipados/)
- [Chromium push endpoint constants](https://chromium.googlesource.com/chromium/src/+/refs/tags/148.0.7734.4/components/push_messaging/push_messaging_constants.cc)
- [Mozilla push endpoints](https://mozilla-services.github.io/autopush-rs/http.html)
