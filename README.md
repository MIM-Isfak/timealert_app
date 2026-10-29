# TimeAlert

Personal schedules and a shared batch timetable with representative ownership,
approved student memberships, version history, acknowledgements, and Web Push
reminders. CSV, Excel, and text-based PDF imports reuse the existing parser; no OCR.

## Pilot architecture

| Component | Host | Configuration |
| --- | --- | --- |
| React / Create React App | Cloudflare Pages | Root `frontend`, Node 22, build `npm ci && npm run build:pilot`, output `build` |
| Spring Boot / Java 17 | Render Free Web Service | [render.yaml](render.yaml), Docker build, `pilot` profile, `/actuator/health` |
| PostgreSQL | Neon Free | External JDBC connection with SSL; no Render database resource |
| Web Push reminders | Cloudflare Worker + SQLite Durable Object | [Worker configuration](notification-service/wrangler.jsonc), isolated `pilot` namespace |

Spring authenticates users and synchronizes timetable versions, memberships and
preferences to the Worker. Durable Object alarms run scheduled jobs independently
of Spring's uptime and the browser tab. Bridge failures remain pending until
synchronization succeeds. Personal events stay separate. Legacy scheduled personal
email reminders are explicitly disabled for the pilot; no SMTP account is required.

Approved students have next-event countdowns, today/current-week views in their
batch timezone, four saved reminder options, and authenticated `.ics` downloads.
Calendar files are snapshots with stable event IDs, versions and cancellation
status. Export again after changes; calendar applications differ in handling
reimports. Exports contain no student identities, public access tokens or alarms.

## Deployment checklist — account confirmation required

This repository does not create accounts or deploy Pages, Render or Neon
automatically. Render automatic deployments are off. Keep Pages automatic builds
off until approval. Use the feature branch and an approved commit; merging into
`main` is unnecessary.

1. **Confirm accounts and names.** Confirm the Cloudflare account and final Pages
   project/domain, Render workspace/service name/region, Neon project/branch/database,
   owner bootstrap email, and release commit. Enter secrets through provider
   dashboards or a private local secret store, never Git or chat.
2. **Establish the origin and isolated storage.** After confirmation, reserve the
   Pages project without publishing a build to establish its final HTTPS origin.
   Create/select the empty Neon Free database. Install the four secrets below in
   the separate `pilot` Worker environment and deploy it only after confirmation.
   Never reuse test subscriptions/namespaces with a new database: user IDs overlap.
3. **Deploy Render from `render.yaml`.** Set its service name/region to the confirmed
   choices before applying (an omitted region uses Render's default). Enter the private environment values below.
   Use JDBC format, e.g. `jdbc:postgresql://YOUR_NEON_HOST/YOUR_DATABASE?sslmode=require`,
   with username/password supplied separately. `sslmode=require` encrypts the
   connection; use `verify-full` with trusted CA configuration when certificate
   identity verification is required. Verify `/actuator/health` returns only
   `{"status":"UP"}` and CORS accepts only the final Pages origin. Initial pilot
   schema creation uses Hibernate `update`; back up Neon before later releases.
   Versioned migrations remain a broader-production boundary. Render builds the
   Docker image; local Maven packaging does not test that image.
4. **Publish Pages after confirmation.** Use root `frontend`, Node 22, build
   `npm ci && npm run build:pilot`, output `build`, production branch
   `feature/timealert-student-alerts`. Set `REACT_APP_API_URL` to the final Render
   origin plus `/api`; rebuild whenever it changes. Verify login, membership,
   timetable, countdown/views, calendar download and notification settings. Real
   users and real-device push tests require separate approval. Health does not
   prove push readiness or device delivery.

### Render environment (server only)

| Name | Value / handling |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `pilot` (Blueprint) |
| `LEGACY_EMAIL_REMINDERS_ENABLED` | `false` (Blueprint) |
| `PORT` | Supplied by Render; Spring binds `0.0.0.0:$PORT` |
| `DB_URL` | Neon JDBC URL with SSL enabled; no embedded credentials |
| `DB_USERNAME`, `DB_PASSWORD` | Private Neon credentials |
| `JWT_SECRET` | Strong random value of at least 32 characters; retain across releases |
| `ALLOWED_ORIGINS` | Single final HTTPS Pages origin, no path, wildcard, comma or trailing slash |
| `PUSH_SERVICE_URL` | Isolated pilot Worker HTTPS origin |
| `PUSH_SERVICE_SECRET` | Exactly the Worker's `INTERNAL_API_SECRET` |
| `OWNER_EMAILS` | Confirmed bootstrap owner email; does not itself register a user |

### Worker secrets (Cloudflare only)

Install `INTERNAL_API_SECRET`, `VAPID_PRIVATE_KEY`, `VAPID_PUBLIC_KEY` and
`VAPID_SUBJECT` as secrets for the selected environment. Keep VAPID keys stable;
replacing them requires devices to subscribe again. The browser receives only the
public key through Spring. No Worker secret belongs in a `REACT_APP_*` variable.
See [Worker instructions](notification-service/README.md).

The existing `test` Worker uses its existing secrets with
`REMINDER_DELIVERY_ENABLED=false`: pending reminders are retained and alarms are
disarmed during deployment checks. Resuming requires a separately approved change
to `true`, deployment and reminder resynchronization. Manual test pushes remain
an explicit separate operation. The prepared `pilot` environment enables alarms,
uses its own namespace, and is not deployed by build commands.

## Free-tier limitations

- Render Free services sleep when idle; the next request can take about a minute
  to start the backend. Login, changes and reminder synchronization can be delayed.
  Do not use synthetic keep-alive polling. Already-scheduled Worker jobs continue
  independently. See [Render Free limits](https://render.com/docs/free).
- There is no exact-second delivery guarantee. Alarms, retries, connectivity,
  provider throttling and browser/OS policies can delay or suppress notifications.
  Devices need internet access for Web Push. Closing a tab is supported; OS
  permission, browser support and background restrictions still apply.
- Provider acceptance is **not confirmed device delivery**. Successful jobs record
  acceptance only. Actual desktop/mobile timing must be verified on the authorized
  pilot environment.
- Pages, Workers/SQLite Durable Objects and Neon have free quotas. Exhausted quotas
  or suspended compute can interrupt service. Monitor dashboards and keep backups.
  No paid plan is selected here. See [Cloudflare Durable Object pricing](https://developers.cloudflare.com/durable-objects/platform/pricing/)
  and [Neon plans](https://neon.com/pricing).

Configuration references: [Render Blueprint](https://render.com/docs/blueprint-spec),
[Pages builds](https://developers.cloudflare.com/pages/configuration/build-configuration/),
[Neon JDBC](https://neon.com/docs/guides/java).

## Local setup and verification

Requirements: Java 17, Node 22, PostgreSQL. Copy `backend/.env.example` and
`frontend/.env.example` to ignored `.env` files. Supply a strong local `JWT_SECRET`
and local database credentials, then create the database; Hibernate creates tables.
Use `LEGACY_EMAIL_REMINDERS_ENABLED=false` for SMTP-free local development. An
unavailable Worker bridge shows synchronization pending without blocking timetable
publication. Local Worker setup is documented in its README.

```powershell
# backend
.\mvnw.cmd spring-boot:run
.\mvnw.cmd package  # backend tests + executable JAR

# frontend
npm.cmd ci
npm.cmd start
npm.cmd test -- --watchAll=false --runInBand
# Set the intended HTTPS REACT_APP_API_URL for this build; no publishing:
npm.cmd run build:pilot

# notification-service; tests mock outbound push
npm.cmd ci
npm.cmd test
npm.cmd exec -- wrangler deploy --env test --dry-run --outdir dist-test
npm.cmd exec -- wrangler deploy --env pilot --dry-run --outdir dist-pilot
```

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| **Frontend** | React 19, Axios, Lucide React icons, Create React App |
| **Backend** | Spring Boot 3.5, Spring Security, Spring Data JPA, Spring Mail |
| **Authentication** | JWT (JJWT 0.11.5), BCrypt password hashing |
| **Database** | PostgreSQL (migrated from MySQL) |
| **File Parsing** | Apache Commons CSV, Apache POI (Excel), Apache PDFBox 3.x (PDF) |
| **Email** | Gmail SMTP via Spring Mail (`spring-boot-starter-mail`) |
| **Deployment (Frontend)** | Vercel (`vercel.json` configured) |
| **Deployment (Backend)** | Any JVM host (Render, Railway, Heroku) via environment variables |
| **Build Tools** | Maven Wrapper (`mvnw`), npm |

---

## Local Setup

### Prerequisites

- Java 17+
- Node.js 18+ and npm
- PostgreSQL running locally (default: `localhost:5432`)

### 1. Clone the repository

```bash
git clone https://github.com/your-username/time-alert-app.git
cd time-alert-app
```

### 2. Configure the backend

```bash
cd backend
cp .env.example .env
```

Edit `backend/.env` and fill in your values:

```env
DB_URL=jdbc:postgresql://localhost:5432/timealert
DB_USERNAME=postgres
DB_PASSWORD=your_postgres_password

PORT=8080
ALLOWED_ORIGINS=http://localhost:3000

# Generate with: openssl rand -hex 32
JWT_SECRET=your_64_char_random_secret_here

# Gmail App Password (https://myaccount.google.com/apppasswords)
MAIL_USERNAME=your-gmail@gmail.com
MAIL_PASSWORD=xxxx xxxx xxxx xxxx
```

> **Note:** `JWT_SECRET` **must** be set to a strong random value — the app will refuse to start if the known default placeholder is used.

### 3. Create the database

```sql
-- In psql or pgAdmin:
CREATE DATABASE timealert;
```

Hibernate auto-creates tables on first run (`ddl-auto=update`).

### 4. Run the backend

```bash
# From the backend/ directory:
.\mvnw spring-boot:run        # Windows
./mvnw spring-boot:run        # macOS / Linux
```

The API will be available at `http://localhost:8080`.

### 5. Configure the frontend

```bash
cd ../frontend
cp .env.example .env
```

`frontend/.env` should contain:

```env
REACT_APP_API_URL=http://localhost:8080/api
```

### 6. Run the frontend

```bash
npm install
npm start
```

The app opens at `http://localhost:3000`.

---

## API Endpoints

| Method | Path | Description | Auth Required |
|--------|------|-------------|:---:|
| `POST` | `/api/auth/register` | Register a new account | ❌ |
| `POST` | `/api/auth/login` | Log in, receive JWT | ❌ |
| `POST` | `/api/auth/demo` | Guest demo login (seeded events, no real emails) | ❌ |
| `GET` | `/api/events` | Fetch all events for the authenticated user | ✅ |
| `POST` | `/api/upload` | Upload a timetable file (CSV / Excel / PDF) | ✅ |
| `DELETE` | `/api/events/clear` | Delete all events for the authenticated user | ✅ |
| `DELETE` | `/api/events/{id}` | Delete a single event by ID | ✅ |
| `GET` | `/actuator/health` | Health check (no auth, returns `{"status":"UP"}`) | ❌ |

**All authenticated endpoints** require an `Authorization: Bearer <jwt_token>` header.  
**Rate limits:** auth/upload endpoints — 30 req/min per IP; all other API — 120 req/min per IP. Exceeded requests return `429` with `Retry-After: 60`.

### CSV Upload Format

Headers must be exactly: `Subject,Description,Date,StartTime,EndTime,Location`

```csv
Subject,Description,Date,StartTime,EndTime,Location
Database Lecture,Chapter 5 — Normalization,2026-09-01,09:00,10:30,Room 204
```

Dates: `YYYY-MM-DD`. Times: `HH:MM` (24-hour). Description and Location are optional.

> **Note for PDF uploads:** PDFBox extracts text and splits on commas — field values must not contain commas.


---

## License

This project is licensed under the **MIT License** — see the [LICENSE](./LICENSE) file for details.
