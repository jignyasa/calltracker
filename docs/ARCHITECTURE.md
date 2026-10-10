# Architecture

Single Android module, **MVVM + Clean Architecture**, Hilt for DI, Coroutines/Flow throughout. The database (Room,
encrypted with SQLCipher) is the **single source of truth**: screens only observe it, and everything that touches the
network writes its result back into it.

## Layers

```mermaid
flowchart LR
    subgraph UI["ui/  (Fragments, ViewModels, adapters)"]
        L[Call activities] --- D[Call details + log form] --- Q[Sync queue] --- S[Settings · Number rules · About]
        N[Work call? notification · Classify dialog · after-call form]
    end
    subgraph DOMAIN["domain/  (pure Kotlin)"]
        UC[Use cases<br/>Sweep · Classify · SetClassification · SyncGate ·<br/>Reclassify · ResolveCrm · CreateLead · SaveLog · Upload]
        RI[Repository + requester interfaces]
        M[Models]
    end
    subgraph DATA["data/"]
        ROOM[(Room + SQLCipher)]
        API[Retrofit CRM API]
        CL[CallLog reader]
        PREF[Settings prefs]
    end
    subgraph BG["sync/ + telephony/"]
        W[WorkManager workers]
        RX[Broadcast receivers]
    end
    UI --> DOMAIN
    BG --> DOMAIN
    DATA -. implements .-> RI
    UC --> RI
```

| Package | Responsibility | Rule |
|---|---|---|
| `domain/` | Models, repository **interfaces**, use cases (all business rules), small requester interfaces (`SyncRequester`, `CrmLookupRequester`, `CallPromptDismisser`) | No `android.*` imports → every rule is JVM-testable. Paging's core (`androidx.paging`) is pure Kotlin and allowed. |
| `data/` | Room DAOs/entities, SQLCipher key, CallLog reader, Retrofit API + DTOs, repository implementations | Implements domain interfaces; never decides policy. |
| `sync/` | `CoroutineWorker`s and their schedulers | Workers are thin: they call one use case and map the result to success/retry. |
| `telephony/` | `PHONE_STATE` and boot receivers | Triggers only — they read no call data. |
| `ui/` | Fragments (XML + ViewBinding), ViewModels (`StateFlow` state, `Channel` one-off events), notifications, the after-call activity | Observes Room; never shows network results directly. |
| `di/` | Hilt modules (DB, network, dispatchers, application scope, interface bindings) | |

## The pipeline: from a phone call to the CRM

The product owner's call flow: **classify first, ask once per number, search the CRM only for professional calls.**

```mermaid
flowchart TD
    T1[PHONE_STATE → IDLE] --> SS
    T2[CallLog content-URI trigger] --> SS
    T3[Boot / app update / app open] --> SS
    T4[Hourly safety net] --> SS
    SS[SweepScheduler<br/>unique work] --> SW[CallLogSweepWorker → SweepCallLogUseCase]
    SW -->|read rows past the watermark<br/>pages of 200, one transaction each| CLG[(Device CallLog)]
    SW -->|normalise E.164, classify: saved answer → work SIM → Unsorted,<br/>store, SyncGate.reconcile, advance watermark| DB[(Room)]
    SW --> CLW[CrmLookupWorker → ResolveCrmMatchesUseCase]
    CLW -->|PROFESSIONAL numbers only, ≤50 per run, cache 24 h| CRM[(CRM API)]
    CLW -->|found → link + re-send<br/>not found + phonebook name → create lead| DB
    CLW --> PN{UNCLASSIFIED conversation,<br/>ended < 1 h ago?}
    PN -->|yes, ≤ 3 showing| NOTIF[“Work call?” notification<br/>Professional / Personal / Log]
    NOTIF -->|answer saved for the number<br/>SetCallClassificationUseCase| DB
    UIX[Call details, Classify dialog, after-call form:<br/>classify · log · create lead · rules · work SIM] --> DB
    DB -->|a Professional answer requests a lookup| CLW
    DB -->|BUSINESS only, via SyncGate| OB[(Outbox)]
    OB --> SY[SyncWorker → UploadPendingActivitiesUseCase]
    SY -->|batches ≤100, clientId = idempotency key,<br/>per-item results, generation-guarded| CRM
```

1. **Detection.** Every trigger only *schedules* a sweep; the sweep reads the **CallLog**, which is the source of truth
   (it has the real type, duration and SIM; the `PHONE_STATE` broadcast doesn't). A watermark (`_ID` + date, plus a fixed
   7-day floor) makes sweeps incremental, idempotent and crash-safe.
2. **Classification first, from local signals only** (`ClassifyCallUseCase`): the number's saved answer (a number rule),
   else the work SIM (Professional), else Unsorted. The CRM and saved phone contacts don't decide.
3. **Ask once.** Only numbers with no saved answer, only conversations (incoming/outgoing), only within an hour, never more
   than three prompts showing. Every answer — notification, Classify dialog, call screen — is saved for the number
   (`applyToNumber = true`), and an answer clears the number's open prompts (`CallPromptDismisser`).
4. **CRM, for professional calls only** (`ResolveCrmMatchesUseCase`): `numbersNeedingCrmLookup` lists BUSINESS numbers
   only; a Professional answer requests a lookup at once (`CrmLookupRequester`). Found → the call is linked (and re-sent
   if already uploaded). Not found → the lead is created from the phonebook name ("Create leads automatically", default
   on), else Create lead is offered. Leads and Contacts are both checked first; the server's 409 links an existing record.
5. **Outbox.** A BUSINESS call gets an activity (with a client UUID) and an outbox row; edits re-arm the row with a new
   `generation`.
6. **Upload.** Batches go to `POST /activities/batch`; each item's result is applied on its own, only if its generation
   is unchanged since it was sent. A stale CRM link (record deleted/merged) is healed and looked up again at once.

## The privacy boundary

```mermaid
flowchart LR
    subgraph PHONE["Stays on the phone (encrypted)"]
        ALL[All calls, contact names,<br/>PERSONAL + UNCLASSIFIED calls]
    end
    SG{{SyncGate.reconcile<br/>the ONLY way into the outbox}}
    ALL --> SG
    SG -->|BUSINESS only| OUT[(Outbox)]
    OUT --> CHK{{Uploader re-checks BUSINESS<br/>builds ActivityPayload: contract fields only}}
    CHK --> NET[CRM]
    ALL -. E.164 number, PROFESSIONAL numbers only .-> LOOK[CRM lookup]
    ALL -. phonebook name, only to auto-create a lead<br/>for a professional number, setting on .-> LEAD[CRM new lead]
```

- `SyncGate.reconcile()` is the single caller of `OutboxRepository.enqueue`; every change to a call's classification
  or log calls it **in the same transaction**, so classification and outbox can never disagree. A call that stops being
  BUSINESS is removed from the outbox (unless already uploaded).
- The uploader re-checks BUSINESS right before building the payload (defence in depth) and the payload type holds only
  the API contract's fields — no contact names.
- CRM lookups send only the E.164 number and only for numbers with a BUSINESS call; Personal and Unsorted numbers are
  never looked up, and a number with a PERSONAL rule or a call marked personal by hand is excluded outright.
- A phonebook name leaves the phone only as the name of an automatically created lead, for a professional number, with
  the setting on.
- Logs: counts, HTTP codes and exception types only (`LogHygieneTest`); release builds strip info logging.

## Background work

All background work is **WorkManager** (`CoroutineWorker`), always **unique work**, no long-running services.

| Worker | Started by | Constraints / policy | Does |
|---|---|---|---|
| `CallLogSweepWorker` | PHONE_STATE idle (+3 s), boot, app update, app open, content trigger, hourly periodic | unique `APPEND_OR_REPLACE`; periodic `KEEP` | Imports new CallLog rows; schedules CRM lookup |
| `CallLogChangedWorker` | Content-URI trigger on `CallLog.Calls.CONTENT_URI` (re-armed after every sweep) | unique `REPLACE` | Requests a sweep (catches calls even when PHONE_STATE was suppressed) |
| `CrmLookupWorker` | After a sweep that imported calls (no network constraint, so prompts aren't delayed); a Professional answer, rule or work-SIM change, or a healed stale link (retry work, `CONNECTED`) | `APPEND_OR_REPLACE` / retry `REPLACE` (a new request restarts the backoff), exponential backoff | Looks up professional numbers, links calls, auto-creates leads; posts "Work call?" prompts (≤ 3 showing) and offers the log form |
| `SyncWorker` | Every enqueue (5 s debounce), app start, hourly periodic; **Upload now** on the Sync queue | `CONNECTED`, exponential backoff 30 s; `APPEND_OR_REPLACE` (Upload now: `REPLACE`) | Uploads the outbox |

## Storage

Room database `calltracker.db` (schema v3), **encrypted with SQLCipher**; the 256-bit key is random, generated once, and
stored wrapped (AES-GCM) by a non-exportable Android Keystore key. If the Keystore key is lost, a new database is created
and calls are re-read from the CallLog. Backups and device transfer exclude it (`allowBackup=false`).

| Table | Holds |
|---|---|
| `calls` | One row per imported CallLog row (unique on CallLog id + start), normalised number, type, times, SIM, classification + its source, linked CRM record |
| `activities` | The CRM activity of a BUSINESS call: client UUID (idempotency key), log fields, `updated_at`, server id/version |
| `outbox` | One row per activity to upload: status, `generation`, attempts, last error |
| `number_rules` | The **saved answer** per number (Professional or Personal): every user answer, plus Number rules entries |
| `crm_cache` | Last CRM answer per number (including "not in CRM") with its time |
| `sweep_state` | The CallLog watermark and the 7-day floor |

Settings (work SIM, create leads automatically, dismissed hints) are in plain `SharedPreferences` — nothing sensitive,
and readable before the database is opened. Schema history: `app/schemas/`; migrations in `data/local/Migrations.kt`
(v2 → v3 turns calls the CRM or a saved contact used to decide into saved answers, so nothing flips on upgrade).

## Screens

Single activity (`MainActivity` implements `AppNavigator`) with plain fragment transactions; a handful of screens didn't
justify a navigation library. A side menu (on the list only) opens: **Call activities** (paged from Room; All /
Professional / Personal / Unsorted tabs filter in SQL; a "waiting to upload" banner opens the Sync queue; no toolbar
actions) → **Call details** (classify, CRM record, log form, create lead, upload status) · **Sync queue** (Pending /
Failed / Synced tabs, offline banner, Upload now) · **Number rules** (saved answers) · **Settings** (work SIM,
notifications, after-call screen, create leads automatically, battery/OEM guidance, privacy) · **Why this approach?**
Dialogs that return data are `DialogFragment`s with the Fragment Result API: Classify call (opened from a "Work call?"
notification), Create new lead and Call saved (full screen, `ui/common/FullScreenDialogFragment`). The after-call form
(`PostCallActivity`) pops up over other apps after a professional call, or opens from the notification's Professional
button; it offers Create lead for unknown numbers.
Look and feel: blue app bar, colour tokens in `values/colors.xml` + `values-night/colors.xml`, shared avatars and
pills in `ui/common/CallVisuals`. The UI says **Professional** for `Classification.BUSINESS`; code and the CRM keep BUSINESS.
