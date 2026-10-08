# Mock CRM server

A small Express server that stands in for the CRM backend so the app's lookup, lead-creation and sync paths
can be exercised end to end, including failure modes.

```bash
cd mock-server
npm install
npm start            # http://localhost:3000, data persisted to mock-server/data/db.json
npm test             # node:test suite (in-memory)
scripts/demo.sh      # curl walkthrough against a running server
```

**Reaching it from the app**
- Emulator: `http://10.0.2.2:3000/` (the app's default `CRM_BASE_URL`).
- Physical device over USB: `adb reverse tcp:3000 tcp:3000`, then use `http://localhost:3000/`.

## Environment

| Var | Default | Meaning |
|---|---|---|
| `PORT` | `3000` | Listen port (binds `0.0.0.0`) |
| `API_TOKEN` | `dev-token` | Expected `Authorization: Bearer <token>` |
| `DATA_FILE` | `data/db.json` | JSON persistence file; `memory` = no persistence |
| `LATENCY_MS` | `0` | Delay added to every API request |
| `FAILURE_RATE` | `0` | 0..1 chance an API request returns 503 |
| `ITEM_FAILURE_RATE` | `0` | 0..1 chance a single batch item returns `ERROR` |

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

The `/__admin` routes need no auth and are never subject to faults.
