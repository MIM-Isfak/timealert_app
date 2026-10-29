# TimeAlert

> Shared timetables and smart reminders for students.

TimeAlert helps students view their latest class timetable, track changes, and receive reminders. Class representatives manage shared events for approved students.

## Why TimeAlert?

Timetable updates can get lost in WhatsApp groups, spreadsheets, and PDF files. TimeAlert keeps class times, room changes, cancellations, and exams in one place.

Representatives add or import updates; the application does not automatically read WhatsApp messages or online spreadsheets.

## User Roles

| Role | Main responsibility |
|---|---|
| OWNER | Grant or remove representative access |
| REP | Manage batches, approve students, and publish timetables |
| STUDENT | Join a batch, view events, and acknowledge updates |

## How It Works

1. The OWNER grants REP access.
2. The REP creates a batch and shares its invitation code.
3. Students register and request to join.
4. The REP approves students and publishes timetable events.
5. Students view events, acknowledge updates, and enable reminders.

Draft events remain hidden from students. Editing an event creates a new version and resets its current acknowledgement count.

## Features

- Secure registration and login
- Batch invitation and student approval
- Personal schedules and shared batch timetables
- Manual and weekly event creation
- CSV, Excel, and text-based PDF imports
- Import previews with skipped-row reasons
- Event publishing, editing, history, and cancellation
- Student acknowledgements
- Web Push reminder preferences
- Next-event countdown and today/week views
- Calendar `.ics` export
- Search and dark mode

## Technology

| Component | Technology |
|---|---|
| Frontend | React, Axios |
| Backend | Java 17, Spring Boot |
| Database | PostgreSQL |
| Security | Spring Security, JWT, BCrypt |
| Notifications | Cloudflare Worker, Web Push, SQLite Durable Objects |
| File parsing | Commons CSV, Apache POI, PDFBox |

The frontend calls the Spring Boot API. PostgreSQL stores application data, while the Cloudflare Worker handles synchronized reminder jobs.

## Project Structure

```text
backend/                Spring Boot API and tests
frontend/               React application and tests
notification-service/   Web Push service and tests
PROJECT_STATUS.md       Development and verification notes
render.yaml             Prepared deployment configuration
```

## Local Setup

### Requirements

- Java 17
- Node.js 22 and npm
- PostgreSQL
- Git

### 1. Clone

```powershell
git clone https://github.com/MIM-Isfak/timealert_app.git
Set-Location timealert_app
```

### 2. Create the database

Run in PostgreSQL:

```sql
CREATE DATABASE timealert_dev;
```

### 3. Configure the backend

Copy `backend/.env.example` to `backend/.env`. Edit the existing file if already configured.

```env
DB_URL=jdbc:postgresql://localhost:5432/timealert_dev
DB_USERNAME=postgres
DB_PASSWORD=your_database_password
PORT=8080
ALLOWED_ORIGINS=http://localhost:3000
JWT_SECRET=replace_with_a_secure_random_secret_at_least_32_characters
OWNER_EMAILS=owner@example.com
LEGACY_EMAIL_REMINDERS_ENABLED=false
```

Register using the configured owner email to access owner features. The setting does not create an account automatically.

Local Hibernate configuration may create or update tables. Use a development database.

### 4. Start the backend

From `backend`:

```powershell
.\mvnw.cmd spring-boot:run "-Dmaven.repo.local=$env:USERPROFILE\.m2\repository"
```

Health check: http://localhost:8080/actuator/health

### 5. Start the frontend

Copy `frontend/.env.example` to `frontend/.env` and set:

```env
REACT_APP_API_URL=http://localhost:8080/api
```

In a separate terminal, from `frontend`:

```powershell
npm.cmd ci
npm.cmd start
```

Open http://localhost:3000.

Press `Ctrl+C` in both terminals to stop the application.

## Timetable Import

Use these columns:

```csv
Subject,Description,Date,StartTime,EndTime,Location
Database Systems,SQL practice,2026-11-03,09:00,10:30,Lab 2
```

- Dates: `YYYY-MM-DD`
- Times: `HH:MM` in 24-hour format
- Description and location are optional
- ISO-formatted text is recommended for Excel dates and times
- PDF import expects comma-separated text; scanned PDFs are unsupported

## Notifications and Calendar

Web Push requires a configured notification service, browser permission, and internet connectivity. The TimeAlert tab can be closed, but browser and device restrictions may affect delivery.

Reminder synchronization can remain pending until the backend connects to the Worker. Exact delivery time is not guaranteed, and provider acceptance does not prove device delivery.

Calendar exports are snapshots without reminder alarms. Export again after timetable changes and configure calendar reminders separately.

Setup: [Notification Service README](notification-service/README.md)

## Tests

Run commands from the corresponding folder.

Backend:

```powershell
.\mvnw.cmd test "-Dmaven.repo.local=$env:USERPROFILE\.m2\repository"
```

Frontend:

```powershell
npm.cmd test -- --watchAll=false --runInBand
npm.cmd run build
```

Notification service:

```powershell
npm.cmd ci
npm.cmd test
```

## Deployment and Status

Deployment configuration is prepared for Cloudflare Pages, a Java backend host, Neon PostgreSQL, and Cloudflare Workers. The complete application is not currently publicly hosted.

Local account, batch approval, timetable publication, acknowledgement, and calendar download flows have been checked. Automated tests and builds have also been performed.

Real-device mobile notifications and production reminder timing still require verification.

See [PROJECT_STATUS.md](PROJECT_STATUS.md) and [render.yaml](render.yaml) for details.

## Security

Passwords use BCrypt, authenticated requests use JWT, and the backend checks roles, ownership, and batch membership.

Never commit `.env` files, database passwords, JWT secrets, or Worker private keys. Use the environment example files as configuration references.

## Contributing

Create a feature branch, make a focused change, run the relevant checks, and open a Pull Request.

Report bugs through [GitHub Issues](https://github.com/MIM-Isfak/timealert_app/issues). Remove private values from error messages and screenshots.

## License

Licensed under the MIT License. See [LICENSE](./LICENSE).
