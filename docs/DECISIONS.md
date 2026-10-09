# Design decisions, trade-offs, assumptions, limitations

## 1. Business vs personal: how it's decided, and why

**Goal from the spec:** personal calls must never be synced, business calls should be ready to log, and the user should
be bothered as little as possible.

**Approach: a layered rule engine that decides on its own whenever it has a signal, and asks only when it doesn't.**

| Order | Signal | Result | Why this order |
|---|---|---|---|
| 1 | The user's rule for this number ("always business / always personal") | that | The user's explicit word beats any guess. |
| 2 | The number is a CRM Lead or Contact | **Business** | The strongest objective signal: the company already deals with this person. Beats "saved contact" because reps save customers in their phonebook too. |
| 3 | The call used the **work SIM** (optional setting) | **Business** | Very common in India: dual-SIM phones with one company SIM. |
| 4 | The number is a saved phone contact and not in the CRM | **Personal** | Friends and family are in the phonebook; customers usually are in the CRM. |
| 5 | Nothing | **Unsorted** → one notification right after the call: **Business / Personal / Log** | Asking once, in context, is cheaper than a form later. |

- **Default-deny.** Anything not positively Business stays on the phone. A mistake can only ever cause a missed upload
  (fixable with one tap), never a leaked personal call.
- **A choice made by hand is never overridden** — not by a rule, a CRM match or the work SIM.
- **"Always for this number"** turns one answer into a rule, so a recurring number is asked about once.
- **Prompts are rare by construction:** only after the CRM lookup (a known customer is never asked about), only for calls
  that ended in the last hour (no flood after a reboot or the first 7-day import), at most 3 at once, and the lock
  screen shows a generic text.
- **Trade-off:** looking up saved contacts in the CRM sends their number (only the E.164 number) to the company's CRM.
  This is what lets "in the CRM" beat "saved contact" — without it, every customer saved in the phonebook would be
  silently personal. Numbers the user marked personal are never looked up.

## 2. Detection: CallLog is the source of truth

- **The `PHONE_STATE` broadcast is only a trigger.** It carries no duration, type or SIM, arrives twice on some phones,
  and OEM battery managers suppress it. The **CallLog** has everything and is written by the system for every call.
- **Watermark sweep** (`_ID` and date, with a 2-minute overlap, plus a 7-day floor fixed at the first sweep): idempotent
  (unique on CallLog id + start), crash-safe (one transaction per page of 200 advances the watermark with the data), and
  it catches long calls written after shorter later ones, and new calls after the user wipes the log. The floor stops a
  restored call-history backup from flooding the app (found on a device).
- **Many triggers, one sweep:** PHONE_STATE idle, a WorkManager **content-URI trigger on the CallLog** (wakes the app
  even when the broadcast never came), boot, app update, app open and an hourly safety net — all collapse into one unique
  sweep.
- **No call-recording, no accessibility service, no default-dialer role** — only standard, Play-policy-friendly APIs
  (`READ_CALL_LOG`, `READ_PHONE_STATE`).
- **Dual SIM:** the CallLog's `PHONE_ACCOUNT_ID` is mapped to a slot via the subscription id, the ICCID, and on Android
  11+ Telecom's phone accounts — Samsung stores the ICCID, which apps can't read since Android 10 (found on a device).

## 3. Sync: outbox + idempotency + generations

- **Outbox pattern.** Logging never waits for the network: the activity and its outbox row are written in the same
  transaction as the classification. Uploads happen later, in the background.
- **Idempotency:** each activity gets a UUID once, on the phone; it's the item's idempotency key on the server. A lost
  response followed by a retry is answered `DUPLICATE` — verified by crashing the app mid-upload on a device.
- **Generations:** every (re)queue bumps a counter; a result only marks an item done if the generation it was sent with
  is still current. An edit made during an upload is therefore re-sent, never silently dropped.
- **Per-item results, never all-or-nothing:** `CREATED/DUPLICATE/UPDATED` → synced; `CONFLICT` → last write wins on
  `updatedAt` (the server's copy replaces the local one if newer); `INVALID` → permanent failure shown on the call with a
  Retry button; `ERROR` → retried. Batch-level failures (no network, 5xx, 429, 401) keep every item.
- **Two fixes found on a device:** a CRM record deleted/merged on the server used to block a call forever (the link is
  now dropped, the call re-checked, and re-queued only if still business); and a CRM copy stamped by a clock running
  ahead used to swallow later edits (a save is now always stamped after the version it replaced).
- **Battery and network:** WorkManager with a network constraint and exponential backoff (30 s doubling), a 5 s debounce
  so a burst of edits is one run, batches of ≤100, ≤1000 items per run, unique work everywhere, hourly safety net.

## 4. Storage and security

- **Room as the single source of truth** — the UI only observes the database, so offline and online look the same.
- **SQLCipher** with a random 256-bit key wrapped by a non-exportable **Android Keystore** key (raw key, no PBKDF2 → fast
  open on cheap phones). Call history is sensitive; a lost/stolen unlocked phone shouldn't expose it via file access.
- **No backups** of the database (`allowBackup=false`, excluded from device transfer); on Keystore loss the DB is
  recreated and re-read from the CallLog — data loss is limited to notes not yet uploaded.
- **Logs** carry no numbers, names or notes (enforced by a test); release builds strip info logging; the debug HTTP log
  redacts the phone parameter.

## 5. Tech choices

| Choice | Why | Alternative considered |
|---|---|---|
| Kotlin, single module | Fast to build and navigate for this size; package boundaries enforce the layers | Multi-module: worth it for a team, overhead here |
| XML views + Material 3 | Mature on low-end devices; smaller APK and lower memory than a Compose runtime for a forms-and-lists app | Compose |
| MVVM + Clean Architecture | Business rules (privacy, sync) in pure Kotlin → fast, exhaustive JVM tests; Android only at the edges | — |
| Hilt | Standard DI on Android with first-class WorkManager/ViewModel support | Manual DI / Koin |
| Room + SQLCipher | Typed queries, migrations, Flow + Paging integration; encryption at rest | SQLDelight |
| WorkManager | Survives process death and reboots, respects Doze, has network constraints and backoff — exactly the sync model | Foreground service (battery, notification, Play policy) |
| Retrofit + OkHttp + kotlinx.serialization | De-facto standard; no reflection-heavy JSON | Ktor |
| Paging 3 | Thousands of calls without loading them all; filters in SQL | Manual limits |
| libphonenumber | Correct E.164 normalisation for every Indian format (`0…`, `+91 …`, spaces) — matching depends on it | Regex |
| Node/Express mock CRM | A real HTTP contract with idempotency, per-item results and fault injection to test against | Hard-coded fakes |
| No navigation library | Five screens; plain fragment transactions are simpler | Navigation component |

## 6. Assumptions

- The CRM API looks like the mock's contract (`mock-server/README.md`): phone lookup, idempotent lead creation, and a
  batch activity endpoint with per-item results, `clientId` as the idempotency key and `updatedAt` for conflicts.
- Phone numbers are Indian by default (`IN` region); other countries work when stored with a country code.
- A rep uses one CRM account per phone; authentication is a static bearer token here (a real app would have a login and
  token refresh).
- Calls older than 7 days at install time are not wanted.
- A follow-up is a date (no time); stored as the local start of that day.
- The user grants call-log and phone permissions — without them the app can't work, and says so.

## 7. Known limitations

- **Phone-maker battery screens** were verified on Samsung only; the others use known component names with a fallback to
  the app's info page. Some OEMs can still kill background work despite all settings.
- **Real incoming calls** were not tested on a device (no second phone); incoming/missed/rejected were verified with
  CallLog rows written by the debug tool, which is exactly what the app reads.
- **An activity already uploaded can't be deleted from the CRM** by the app; marking it personal stops further updates
  and the UI warns about it.
- **Unsaved notes are lost** if Android kills the app in the background before Save (they survive rotation).
- **Database key creation and opening run on the main thread** on first use — a few tens of milliseconds on low-end
  phones.
- **Calls imported before the dual-SIM fix** keep an empty SIM slot; changing the work SIM re-checks only the last 7 days.
- **A CRM record created on the server side** is noticed on the number's next call (every completed call is searched
  again), or within 24 h if it doesn't call again; creating the lead in the app is immediate. Either way, calls already
  uploaded are re-sent with the new link.
- **Release APKs here are debug-signed** (no keystore in the repo).
- **targetSdk is 36**, compileSdk 37 — raising the target needs its own review.

## 8. Future improvements

- **Login and token refresh**, per-user CRM accounts, and server-pushed config (CRM URL, cache TTL).
- **Push-based CRM changes** (FCM or delta sync) instead of a 24 h lookup cache; bulk "is this number known?" lookups.
- **Persist the log-form draft** across process death (today it survives rotation only).
- **Search** in the call list; per-day summaries for the rep.
- **Open the database off the main thread** (async DI provider or a startup initializer).
- **Upload retention**: prune old synced calls and activities after N days to bound storage.
- **Instrumented UI tests** (Espresso) for the main flows and a CI pipeline running unit tests, lint and a release build.
- **More OEM coverage** (Tecno, Infinix, Lava) and an in-app "test background reliability" check.
- **Analytics without personal data** (counts of syncs/failures) to monitor reliability across devices.
