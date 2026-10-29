# TimeAlert frontend

React / Create React App. Use Node 22, `npm ci`, and `npm start` locally. Copy
`.env.example` to the ignored `.env` for local API configuration.

For the confirmed Cloudflare Pages pilot:

| Setting | Value |
| --- | --- |
| Root directory | `frontend` |
| Production branch | `feature/timealert-student-alerts` |
| Node version | `22` (`.node-version`) |
| Build command | `npm ci && npm run build:pilot` |
| Output directory | `build` (Create React App) |
| Build environment | `REACT_APP_API_URL=https://FINAL_RENDER_HOST/api` |

`build:pilot` rejects missing/local/non-HTTPS API URLs and disables source maps.
`REACT_APP_*` values are public: never put database credentials, JWT signing keys,
VAPID private keys or Worker shared secrets in them. API URL changes require a
rebuild. `_redirects` supports SPA routes; `_headers` adds browser headers and
prevents stale service-worker caching.

Keep automatic production/preview builds off until approval. npm build commands
do not create or deploy Pages resources. Follow the [account checklist](../README.md)
before publishing.

Run `npm test -- --watchAll=false --runInBand` and `npm run build:pilot` with an
execution-time API URL. Countdown and today/week grouping use the batch timezone.
Calendar download authenticates with the student's JWT and rechecks approval
server-side; exports are snapshots, not calendar subscriptions.
