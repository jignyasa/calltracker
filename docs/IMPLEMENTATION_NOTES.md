# Implementation notes

This file records what was built in each phase, why it works the way it does, and how it was checked.
Spec: `docs/assignment.pdf` · Plan: `docs/PLAN.md` · Rules: `CLAUDE.md` · Phase 0–3 device test: `docs/TEST_REPORT_PHASE3.md`.

---

## Phase 4: CRM matching, rule engine and post-call prompt (2026-10-09)

### What it does
Every call ends up **Business**, **Personal** or **Unsorted** without the user having to touch it, unless the app genuinely can't tell.

| Precedence | Signal | Result |
|---|---|---|
| 1 | Your rule for the number ("always business / personal") | that |
| 2 | The number is a CRM contact or lead | Business |
| 3 | The call used the work SIM (Settings) | Business |
| 4 | The number is a saved phone contact | Personal |
| 5 | Nothing above | Unsorted; you're asked after the call |

A call you classified by hand is never changed automatically.

### How it works

```
CallLog ─► SweepCallLogUseCase ─(local signals only, CRM *cache*)─► Room
                     │ imported ids
                     ▼
            CrmLookupWorker ─► ResolveCrmMatchesUseCase ─► CrmRepository.lookup (network, cache 24 h)
                     │                    └─► ReclassifyNumberUseCase ─► CallReclassifier ─► SyncGate
                     ▼
            PostCallNotifier: only calls still Unsorted, ended < 1 h ago, max 3
```

- **The sweep never waits for the network.** It classifies from what's on the phone: the rule, the cached CRM answer, the work SIM and the device contact (`GatherClassificationSignals`).
- **CRM lookups run afterwards** in `CrmLookupWorker`. It covers numbers from the last 7 days with no answer, or an answer older than 24 h, up to 50 per run. Lookups go most recent first.
- **Reclassification and the outbox change in the same transaction.** A call that becomes Business is queued, and one that stops being Business is removed from the queue (if not yet uploaded).
- **Offline:** the post-sweep run has no network constraint, so the after-call question still appears right away. Failed lookups go to a separate `crm-lookup-retry` job that waits for the network and backs off exponentially. When it resolves a call, that call's question is removed.
- **Create lead from a call:** `POST /leads` with an `Idempotency-Key` built from the lead's details. A retry after a timeout replays the original response instead of creating a duplicate. If the number already exists (409), the existing record is linked instead.

### Privacy decisions
- **Only the E.164 number is sent** to the CRM lookup. No name, time, duration or notes.
- **Never looked up:** a number with a PERSONAL rule, or with any call you marked Personal by hand (`CallDao.numbersNeedingCrmLookup`).
- **Saved contacts *are* looked up.** That's what lets "in the CRM" outrank "saved contact", as the precedence requires.
- **Debug network log:** request lines only (BASIC level), with the `phone` value shown as `██`. Release builds have no network log.
- **Lock screen:** the after-call notification shows only "Was your last call a work call?"; the number appears after unlock.

### Main files
`domain/usecase/{GatherClassificationSignals, ReclassifyNumberUseCase, ResolveCrmMatchesUseCase, CreateLeadFromCallUseCase, PostCallPromptPolicy}`,
`data/remote/{CrmApi, CrmDtos}`, `data/repository/{CrmRepositoryImpl, SettingsRepositoryImpl}`, `di/NetworkModule`,
`sync/{CrmLookupWorker, CrmLookupScheduler}`, `ui/notifications/{PostCallNotifier, PostCallActionReceiver}`.

### Checked
- **74 JVM tests**, including:
  - lookup resolution and the precedence edge cases
  - create lead (idempotent retry, 409, offline)
  - the repository against MockWebServer
  - the Room lookup query
  - a Robolectric test of the worker
- **On the Samsung A50 against the mock CRM:**
  - a saved contact that is in the CRM became Business with its CRM name
  - unknown numbers got the question (max 3)
  - Business, Personal and Log all worked
  - offline still asked, and once the CRM was back the call became Business and the question disappeared
  - no phone numbers appeared in any log

---

## Phase 5: User interface (2026-10-09)

### Screens

| Screen | What you can do |
|---|---|
| **Call list** | Filter by Business / Personal / Unsorted and by upload status (Pending / Synced / Failed). Business calls show a status badge. New calls appear at the top. ⋮ menu → Number rules, Settings |
| **Call details** | See who called (CRM name, then contact name, then number), when, how long and on which SIM. Mark Business / Personal, optionally "Always for this number". **Create lead** (if the number isn't in the CRM). Log notes, outcome, follow-up date, next action and remarks (Business calls only). See upload status |
| **Number rules** | List rules; add one (number typed in any Indian format); switch business ↔ personal; delete. Each change reports how many calls it updated |
| **Settings** | Work SIM (None / SIM 1 / SIM 2 with carrier names). After-call questions on/off status with a shortcut to notification settings. Battery optimisation status with a shortcut. Privacy summary |

The notification's **Log** button opens that call's details screen with the call already marked Business.

### Making sure nothing gets lost
- **Unsaved notes survive rotation.** The form lives in the ViewModel, and the follow-up date and outcome survive too.
- **Back with unsaved notes** asks "Discard changes?".
- **Your typing is never overwritten.** If the stored log changes elsewhere (e.g. later sync conflict handling), the form reloads only while there are no unsaved edits. The fields are refilled only when the ViewModel's `formVersion` changes.
- **After Save, the form shows exactly what was stored:** trimmed, with empty fields dropped. It then counts as saved.
- **Too-long text** (over 5000 characters) is flagged on the field and nothing is saved.
- **List filters survive process death** (`SavedStateHandle`).
- **Dialogs survive rotation.** Create lead and Add rule are `DialogFragment`s that return their result through the Fragment Result API.
- **Rule and work SIM changes go through the same rule engine** (`CallReclassifier`). Hand-classified calls stay as they are, and the outbox is updated in the same transaction.
  - The work SIM change applies to the last 7 days of calls and to new calls.

### Navigation
Single activity, plain fragment transactions (`AppNavigator`, implemented by `MainActivity`). With five screens, a navigation library wasn't worth a new dependency. Sub-screens get an Up arrow and their own title.

### Fixes made along the way

| Problem | Fix |
|---|---|
| Real calls on Samsung had **no SIM slot**: it stores the ICCID, which apps can't read on Android 10+. The work SIM could never match | On API 30+, map the CallLog's `PHONE_ACCOUNT_ID` through Telecom's phone accounts → subscription → slot. Verified: a real call shows "SIM 1". Calls stored before this fix keep a blank slot |
| Screen titles always said "CallTracker" (`app:title` in the toolbar XML blocks `setTitle`) | Removed it; each screen sets its title |
| `SettingsViewModel` ignored "No work SIM" when nothing was observing its state (compared against a stale `StateFlow` value) | Compares against the stored setting |
| Durations over an hour showed as `62m 05s` | Now `1h 02m` |
| Short codes (121, 198) without a contact name showed as "Private number" | Only real "no number" markers (`""`, `-1`, `-2`, `-3`) show as private |
| The list didn't jump to new calls | Stays at the top when you're already there |
| Lint: overdraw and missing `contentDescription` on list rows; "N calls" strings | Fixed; plurals added |
| Built-in Android icons looked wrong on Samsung | Material ⋮ and + vectors |

### Main files
`ui/{AppNavigator, MainActivity, MainViewModel}`, `ui/calls/*`, `ui/detail/{CallDetailFragment, CallDetailViewModel, CreateLeadDialogFragment}`,
`ui/rules/{RulesFragment, RulesViewModel}`, `ui/settings/{SettingsFragment, SettingsViewModel}`,
`domain/usecase/{CallReclassifier, NumberRuleUseCases, SetWorkSimUseCase}`, `data/device/AndroidSimCardSource`,
`sync/SweepScheduler` (`SweepRequester` interface, so ViewModels test on the plain JVM).

### Checked
- **97 JVM tests** (23 new ViewModel tests with Turbine, plus use-case tests). Stable over repeated runs.
  - Tests wire use cases through `testing/TestUseCases` over `FakeRepositories`, and run `viewModelScope` through `testing/MainDispatcherRule`.
- **Lint:** 0 errors. 7 warnings, all from before Phase 4: dependency versions, the old SDK folder, and the `DatabaseKeyProvider` prefs call.
- **Manual run-through on the A50** (all passed):

| Check | Result |
|---|---|
| List filters, including the empty-filter message | ✅ |
| Detail: Business toggle reveals the form | ✅ |
| Notes, outcome dropdown, follow-up date picker | ✅ |
| Rotation with unsaved edits keeps everything | ✅ |
| Back asks "Discard changes?" | ✅ |
| Save cleans the form and shows "Waiting to upload" | ✅ |
| Create lead (empty name blocked; lead created on the server; CRM name shown) | ✅ |
| Rules: add, switch, delete, empty state | ✅ |
| Settings: real SIMs, notification and battery status | ✅ |
| Work SIM 1 turned a real call Business; clearing turned it back to Personal | ✅ |
| Notification Log → details → Back to list | ✅ |
| Titles and icons after the fixes | ✅ |

### Known limitations (planned later)
- **Uploads don't happen yet** (Phase 6). Business calls show "Waiting to upload".
- **Unsaved notes are lost if Android kills the app in the background.** They survive rotation, but aren't written to disk until Save.
- **No OEM-specific battery screens yet** (Phase 7). The Settings button opens Android's standard battery-optimisation list.
- **The call list isn't paged** (Phase 7 covers large volumes).
- **Changing the work SIM only re-checks the last 7 days**, so older calls keep their classification.

---

## Phase 6: Sync engine (2026-10-09)

### What it does
Every **Business** call is uploaded to the CRM as an activity, including the notes you add later. Uploads survive being offline, the CRM being down, the app being killed, and edits made while an upload is in progress. Nothing that isn't Business is ever sent.

### How it works

```
SyncGate.reconcile ─► outbox (PENDING, generation n) ─► SyncScheduler (unique work, CONNECTED, 5 s debounce)
                                                              │
SyncWorker ─► UploadPendingActivitiesUseCase:  reset stale IN_FLIGHT → claim ≤100 → build payloads → POST /activities/batch
                                               └─ per item, only if the generation is still n:
                                                  CREATED / DUPLICATE / UPDATED → SYNCED (+ server id)
                                                  CONFLICT  → last write wins on updatedAt (CRM copy replaces local if newer)
                                                  INVALID   → FAILED_PERMANENT, reason shown on the call
                                                              (stale lead/contact link → healed and re-queued, see below)
                                                  ERROR     → FAILED_RETRYABLE
                                               whole batch fails (network / 5xx / 429) → all FAILED_RETRYABLE, Result.retry()
```

- **Idempotent:** each activity has a `clientId` UUID generated once on the phone, which is its idempotency key. If a response is lost and the upload is repeated, the CRM answers DUPLICATE, which counts as uploaded.
- **No lost edits:** each (re)queue bumps the outbox row's `generation`. A result only applies if the generation it was sent with is still current. An edit made during an upload stays PENDING and goes out in the next run.
- **One bad item never blocks the batch:** each item's result is applied on its own, in its own transaction.
- **Retries:** WorkManager's exponential backoff (30 s, doubling). An hourly unique periodic job catches anything left over, and every app start or boot requests a run.
- **Crash recovery:** a run first resets IN_FLIGHT rows to PENDING.
- **Large backlogs:** batches of up to 100, and at most 1000 items per run so a single run can't go on for too long.

### Privacy
- **Business check at send time.** `SyncGate` is still the only way into the outbox. The uploader also re-checks that each call is BUSINESS right before building its payload; anything else is removed from the outbox and never sent. A test injects a non-business row directly into the outbox to prove it is refused.
- **Explicit payload.** `ActivityPayload` holds exactly the contract's fields: E.164 number, call type, times, duration, SIM slot, CRM lead/contact id and your log fields. Contact names are never in it.
- **No call details in logs.** The uploader never logs the request; the worker logs counts only.

### Decisions
- **Call types the CRM doesn't accept** are mapped to the nearest one: blocked → REJECTED, voicemail → MISSED, "answered elsewhere" → INCOMING.
- **A Business call with no number** (private or unknown) can't be stored by the CRM. It fails locally with a clear reason and is never sent.
- **Stale CRM link (found on the device).** If the linked CRM lead or contact was deleted or merged, the CRM rejects the activity with "leadId/contactId does not exist", and retrying unchanged would fail forever. The app now:
  - forgets the cached CRM answer;
  - drops the link and re-runs the rule engine for that number, so a call that was Business *only because of that CRM record* leaves the outbox and is never sent;
  - re-queues the call if it is still Business, so it uploads without the link.

  The next CRM lookup re-links the number if the record exists under a new id.
- **Clock skew (found on the device).** If the CRM's copy carries a timestamp from a clock running ahead, last-write-wins would silently drop every local edit until the phone's clock caught up. A save is now stamped `max(now, previous updatedAt + 1)`, so an edit always counts as newer than the version it was based on.

### UI
- **Call list:** "N calls waiting to upload · N failed" under the filters, plus the existing Pending / Synced / Failed filters and badges. ⋮ → **Upload now** runs immediately (once online).
- **Call details:** upload status, with the CRM's reason when it was rejected, and **Retry upload** for rejected calls.

### Main files
`domain/model/Sync.kt`, `domain/repository/{ActivityUploader (+ SyncRequester), OutboxRepository}`,
`domain/usecase/{UploadPendingActivitiesUseCase, RetryUploadUseCase}`, `data/remote/ActivityUploaderImpl`,
`data/local/dao/OutboxDao` (claim, generation-guarded updates), `sync/{SyncWorker, SyncScheduler}`.

### Checked
- **122 JVM tests** (25 new):
  - The upload use case:
    - exact payload, with no contact name
    - a personal call never sent, even when injected into the outbox
    - one bad item never blocking the batch
    - network/5xx failure and recovery
    - duplicate replay
    - an edit during upload
    - conflict, including with a clock that runs ahead
    - crash recovery
    - a call without a number
    - a 250-call backlog sent as 100 + 100 + 50
    - a missing result
    - both stale-link cases
    - retry upload
  - `ActivityUploaderImpl` against MockWebServer
  - Room outbox queries (claim, generation guard, crash reset)
  - `SyncWorker` success/retry mapping
- **Lint:** 0 errors, 7 warnings (all from before Phase 4).
- **On the Samsung A50 against the mock CRM** (all passed):

| Check | Result |
|---|---|
| Queue flushed on install | ✅ |
| Calls linked to leads that no longer exist were rejected and shown ("5 failed", Failed filter, reason, Retry) | ✅ |
| Retry on a hand-classified call healed the link and uploaded it | ✅ |
| Retry on a CRM-only call dropped it to Personal; never sent | ✅ |
| Edit → UPDATED (server version 2) | ✅ |
| Forced 503 → retry after backoff → uploaded (version 3) | ✅ |
| Newer copy edited on the CRM side → CONFLICT → the app shows the CRM's notes and outcome | ✅ |
| Per-item ERROR → "1 call waiting to upload" → uploaded once the fault was cleared | ✅ |
| After the skew fix, an edit made after that conflict reached the CRM (version 5) | ✅ |
| The CRM received only Business test calls: none of the real calls, nothing that turned Personal | ✅ |

### Known limitations (later)
- **Being offline is handled by WorkManager's network constraint.** I tested a server outage on the device, but not airplane mode.
- **A rejected call stays failed** until you tap Retry or edit it. That's deliberate: retrying unchanged would just be rejected again.
- **An activity already uploaded to the CRM can't be deleted from the app.** Marking it Personal stops further updates, and the UI warns about this.
