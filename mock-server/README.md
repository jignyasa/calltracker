# Mock CRM server

A small Express server that stands in for the CRM backend so the app's lookup, lead-creation and sync paths
can be exercised end to end, including failure modes.

```bash
cd mock-server
npm install
npm start            # http://localhost:3000, data persisted to mock-server/data/db.json
npm test             # node:test suite (in-memory; also on Postgres when TEST_DATABASE_URL is set)
scripts/demo.sh      # curl walkthrough against a running server
```

**Reaching it from the app**
- Emulator: `http://10.0.2.2:3000/` (the app's default `CRM_BASE_URL`).
- Physical device over USB: `adb reverse tcp:3000 tcp:3000`, then use `http://localhost:3000/`.
- Any phone, no laptop: the hosted copy (Render + Neon Postgres), see [Hosted deployment](#hosted-deployment-render--neon).

## Environment

| Var | Default | Meaning |
|---|---|---|
| `PORT` | `3000` | Listen port (binds `0.0.0.0`) |
| `API_TOKEN` | `dev-token` | Expected `Authorization: Bearer <token>` |
| `DATA_FILE` | `data/db.json` | JSON persistence file; `memory` = no persistence |
| `LATENCY_MS` | `0` | Delay added to every API request |
| `FAILURE_RATE` | `0` | 0..1 chance an API request returns 503 |
| `ITEM_FAILURE_RATE` | `0` | 0..1 chance a single batch item returns `ERROR` |
| `DATABASE_URL` | unset | Postgres connection string. Set → **hosted mode**: data in Postgres (`src/pgStore.js`, tables from `src/schema.sql`), faults off, `/__admin` locked, rate limit on |
| `ADMIN_TOKEN` | unset | Required for `/__admin/*` (`Authorization: Bearer <token>` or `?key=<token>`). Unset: open locally, 404 in hosted mode |
| `ENABLE_FAULTS` | on locally, off hosted | `true`/`false` overrides; when off, `X-Mock-Fault` is ignored and `/__admin/faults` is gone |
| `RATE_LIMIT_PER_MIN` | `120` (hosted only) | `/api` requests per client IP per minute; above it → 429 + `Retry-After` |
| `TEST_DATABASE_URL` | unset | `npm test` also runs the whole API suite against this Postgres (it **truncates** the tables) |

## API (`/api/v1`, bearer auth)

All timestamps are **epoch milliseconds**. Phone numbers are accepted in common Indian formats and
returned as E.164 (`+91XXXXXXXXXX`).

### `GET /contacts/lookup?phone=`
`200 {"phone":"+91…","match":{"type":"CONTACT"|"LEAD","id":"…","name":"…","company":"…","phone":"…"}}`.
`match` is `null` when there's no record. `400 INVALID_PHONE` if the number can't be parsed.

### `POST /leads` (header `Idempotency-Key: <uuid>` required)
Body `{"name":"…","phone":"…","company":"…"?}` → `201 {"lead":{…}}`.
- Same key + same body → the stored response, plus `Idempotent-Replayed: true`.
- Same key + different body → `422 IDEMPOTENCY_KEY_REUSED`.
- Phone already in the CRM → `409 PHONE_EXISTS` with `match` (not cached under the key).

### `POST /activities/batch`
Body `{"items":[Activity, …]}`, 1–100 items (`413 BATCH_TOO_LARGE` above that). Each item's `clientId` (UUID)
is its idempotency key. Always `200` with one result per item, in order:

```json
{"results":[{"clientId":"…","status":"CREATED","serverId":"act_00001","version":1}]}
```

| status | meaning | client action |
|---|---|---|
| `CREATED` | stored | mark synced |
| `DUPLICATE` | identical payload already stored | mark synced (same `serverId`) |
| `UPDATED` | newer `updatedAt` than stored; overwritten | mark synced |
| `CONFLICT` | stored copy has newer/equal `updatedAt`; `server` holds it | server wins; merge locally |
| `INVALID` | validation failed; `errors[]` | permanent failure, show to user |
| `ERROR` | transient per-item failure | retry later |

Activity fields: `clientId`, `phone`, `callType` (`INCOMING|OUTGOING|MISSED|REJECTED`), `startedAt`, `endedAt`,
`durationSec`, `updatedAt` (required); `simSlot`, `leadId`, `contactId`, `notes`, `outcome`
(`INTERESTED|NOT_INTERESTED|CALL_BACK|NO_ANSWER|WRONG_NUMBER|CONVERTED|OTHER`), `followUpAt`, `nextAction`,
`remarks` (optional). `leadId`/`contactId` must exist.

### `GET /activities`
Lists stored activities (debugging).

## Fault injection

- Per request: header `X-Mock-Fault: 500|503|429|timeout|drop`. `timeout` never responds; `drop` resets the socket.
- At runtime: `POST /__admin/faults` with any of
  `{"latencyMs":2000,"failureRate":0.3,"itemFailureRate":0.2,"forceStatus":503,"forceCount":3}`.
  `GET /__admin/faults` shows the current config.
- `GET /__admin/state` dumps everything; `POST /__admin/reset` restores the seed data.

Locally the `/__admin` routes need no auth (unless `ADMIN_TOKEN` is set) and are never subject to faults.
In hosted mode fault injection is off unless `ENABLE_FAULTS=true`.

## Hosted deployment (Render + Neon)

Same API, same contract; the app's release APK points at it (`https://calltracker-crm.onrender.com/`).

1. **Neon** (neon.tech, free, no card): create a project, copy the **pooled** connection string
   (`postgresql://…-pooler…/neondb?sslmode=require`).
2. Optional, carry the laptop's data over: `DATABASE_URL='<string>' node scripts/import-json.js` (safe to re-run).
3. **Render** (render.com, free, no card): New → Blueprint → this repo. `render.yaml` creates the web service
   `calltracker-crm` (root `mock-server`, health check `/health`) and asks for `DATABASE_URL` and `ADMIN_TOKEN`
   (letters/digits only).
4. Check: `https://calltracker-crm.onrender.com/health` → `{"ok":true}`; data at `/__admin/state?key=<ADMIN_TOKEN>`.

Storage and concurrency: tables keep each record as JSONB, with the API's keys enforced by the database (one lead
per phone, one activity per `clientId`, one response per `Idempotency-Key`). Each lead request and each batch item
runs in its own transaction holding an advisory lock on its key, so retries racing each other, even on two
instances, can't create duplicates.

Free-tier limits: the Render service sleeps after ~15 min idle and the first request takes up to ~1 min (the app
wakes it on start and waits up to 60 s). Neon's compute pauses when idle and resumes in about a second; the data stays.
Logs never contain a phone number (`?phone=` is masked) or the admin key.
