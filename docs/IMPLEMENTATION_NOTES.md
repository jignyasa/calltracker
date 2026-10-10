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

### Follow-up device checks (2026-10-09)
Run after Phase 6 was committed, to cover what had only been unit-tested. All passed.

| Check | Result |
|---|---|
| **Airplane mode:** an edit saved offline stayed "Waiting to upload". The job was held only by `CONNECTIVITY` (0 runs in 40 s) | ✅ |
| **Back online:** after airplane mode was turned off, the edit uploaded by itself without opening the app | ✅ |
| **150-call backlog for one CRM lead**, arriving while sweeps ran | ✅ CRM received exactly 150, no duplicates |
| **App crashed mid-upload:** CRM blocked while 150 calls queued; released with an 8 s server delay; "Upload now"; `am crash` while the 100-item request (21 KB) was in flight | ✅ CRM had stored those 100 but the app never got the answer. On relaunch: "Uploaded 150" (100 DUPLICATE + 50 CREATED). CRM: **exactly 150** |
| **Wrong API token:** HTTP 401 → retry, call kept, nothing stored; correct build reinstalled → it uploaded | ✅ |

**Fixed during these checks:** after failures, the queued upload can sit in exponential backoff for minutes, and "Upload now" was queued *behind* it. "Upload now" now **replaces** the queued run. This is safe even mid-upload: a cancelled run's IN_FLIGHT items are reset at the start of the next run, and anything the CRM already stored comes back as DUPLICATE.

**Test tooling:** the debug seeder's `bulk` action takes `--es number +9190000NNNNN`, so a whole backlog can come from one number.

---

## Phase 7: Hardening (2026-10-09)

### Lint clean, and kept clean
- **Fixed:** an unused colour, the unnecessary `mipmap-anydpi-v26` folder, and the key-storage prefs write (now KTX `edit(commit = true)`; `commit` on purpose, so the key is on disk before the database is created with it). core-ktx was bumped to 1.19.1.
- **`app/lint.xml`, with a reason per exception:**
  - targetSdk stays at 36: raising it opts into a new release's behaviour changes, which needs its own review.
  - "Newer version available" notices are informational, so a library release can't break the build.
  - AGP 9.4.1 needs Gradle 9.6, which is a separate upgrade, so the project stays on AGP 9.2.1.
- **Lint now runs with `warningsAsErrors`.** Result: **0 errors, 0 warnings.**

### Release build
- **R8:** code and resource shrinking, with `proguard-rules.pro` keeping SQLCipher's JNI classes. Release builds strip `Log.v/d/i` entirely.
- **Size:** 10.5 MB, against 18.5 MB for debug. Most of the rest is SQLCipher's native libraries.
- **Checked in the release APK:** no debug seeder, no `WRITE_CALL_LOG`, not debuggable, `allowBackup=false`.
- **Signing:**
  - With `releaseStoreFile`, `releaseStorePassword`, `releaseKeyAlias` and `releaseKeyPassword` as Gradle properties (e.g. in `~/.gradle/gradle.properties`, never in the repo), the release build uses that key.
  - Without them it is signed with the debug key, so reviewers can still install it.
- **Build:** `./gradlew assembleRelease`. For a physical device add `-PcrmBaseUrl=http://localhost:3000/` and run `adb reverse tcp:3000 tcp:3000`.

### Background reliability (battery)
- **Settings → Background reliability:**
  - The battery-optimisation status.
  - **Allow in background**, which shows the system "Stop optimising battery usage?" prompt (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`). Call capture is the app's core function and must run after every call, which is the case this prompt is meant for.
  - On a phone from a known maker, that maker's extra steps and an **Open … settings** button (`OemBatteryGuide`):
    - Xiaomi / Redmi / POCO
    - Oppo / Realme / OnePlus
    - Vivo / iQOO
    - Samsung
    - Huawei / Honor

    Each maker has several known screens, tried in order (they move between OS versions). If none opens, the app's system info page opens instead. The packages are declared in the manifest's `<queries>`; a unit test keeps that list and the guide in sync.
- **Call list:** a "Don't miss calls" card while the app is battery-optimised, with **Fix** (→ Settings) and **Not now** (remembered). It disappears once the app is exempt.

### Large call volumes
- **The call list is paged by the database** (Paging 3.5.2 + room-paging, 40 rows per page), and its filters run in SQL. Before, every call was loaded and filtered in memory.
- **The upload summary is one SQL aggregate** (`CallDao.observeUploadCounts`) instead of counting a full list.
- Everything else was already bounded:

| Part | Bound |
|---|---|
| Sweep | pages of 200 |
| CRM lookups | ≤ 50 per run |
| Uploads | ≤ 100 per batch, ≤ 1000 per run |
| Post-call questions | ≤ 3 |

### No call data in logs
- **Audit:** every log line in the app prints only counts, HTTP status codes or exception *types*. The two places that logged a whole exception (whose message could carry details) now log just its type.
- **`LogHygieneTest`** fails the build if a log statement interpolates a number, name, notes, payload or request field, or passes a Throwable (except key-unwrap failures, which carry no user data).
- **Debug network log:** BASIC level with the `phone` value redacted. The release build has no network log.

### Checked
- **130 JVM tests**, including:
  - paged list filters and order against real Room SQL
  - upload counts
  - the paged ViewModel (`asSnapshot`)
  - the phone-maker guide and its manifest sync
  - log hygiene
- **Release APK on the Samsung A50, against the mock CRM.** Seeded 65 calls (40 + 15 from two CRM leads, 10 unknown) with call-log access off, then installed the **release** build:

| Check | Result |
|---|---|
| Within **7 s** of opening: imported, looked up, classified and uploaded | ✅ CRM received **40 + 15 = 55**; the 10 unknown numbers were never sent |
| Encrypted database, Hilt, WorkManager and Retrofit under R8 | ✅ |
| Release logging | ✅ no app info logs, no network log |
| Exactly 3 after-call questions (newest unknown calls) | ✅ |
| Paged list: 60 flings reached calls from hours earlier, past hundreds of rows | ✅ no ANR or crash |
| Battery card shown while optimised | ✅ |
| Settings shows the Samsung section; "Allow in background" opens the system prompt | ✅ |
| "Open Samsung settings" opens Samsung's own Battery screen | ✅ |
| Once allowed, Settings says "Battery optimisation is off" and the card hides | ✅ |

### Known limitations
- **Phone-maker screens** were verified on Samsung only. The others come from their published component names and fall back to the app's info page.
- **The release APK is debug-signed** unless a keystore is configured.

---

## Phase 8: Docs and delivery (2026-10-09)

- **`README.md`:** what the app does, setup (JDK 17, SDK, Node 20+), mock server, build and install for an emulator and for a physical phone (`adb reverse` + `-PcrmBaseUrl`), first run, the prebuilt APKs, commands, and a table mapping each assignment deliverable to its location.
- **`docs/ARCHITECTURE.md`:** layers, the call-to-CRM pipeline, the privacy boundary, background work and storage, with Mermaid diagrams.
- **`docs/DECISIONS.md`:**
  - the business vs personal reasoning (asked for explicitly by the spec);
  - detection, sync, storage and tech choices, with the alternatives considered;
  - assumptions, known limitations and future improvements.
- **`docs/TESTING.md`:** the test pyramid, how to run each level, what the 130 JVM and 2 on-device tests cover, and the device verification summary.
- **`dist/`:** two R8 release APKs, debug-signed:
  - `CallTracker-1.0-emulator.apk`: CRM at `10.0.2.2:3000`
  - `CallTracker-1.0-device.apk`: CRM at `localhost:3000` via `adb reverse`
- **`TEST_REPORT_PHASE3.md`:** now notes which of its findings were fixed later.

---

## Real business call, end to end (2026-10-09)
A real dialled call on the A50 to a number added to the CRM as lead "Test Customer":
- The **outgoing call** (SIM 2) was imported 7 s after dialling. The person called back, and that **real incoming call** (answered, 9 s) was imported 4 s after it ended. This was the first real incoming call tested.
- The number is a saved phone contact, so both calls were first **Personal**. Once the CRM lookup ran, "in the CRM" outranked "saved contact": both, plus 4 earlier calls with that number from the last 7 days, became **Business**, were linked to `lead_00003` and uploaded.
- A note added in the app reached the CRM as version 2. The number appeared in 0 app log lines.
- **Fixed:** the first lookup failed because the USB port forward had dropped. Its retry was queued behind an older CRM-lookup retry job whose backoff had grown to about 12 minutes during the day's outage tests. `CrmLookupScheduler.scheduleRetry` now **replaces** that job, so a new call restarts the backoff at 30 s.

---

## CRM link after upload (2026-10-10)
A check against the spec's "search the number when a call completes, link it, or create a lead" found two gaps:
- **A link added after upload never reached the CRM.** `SyncGate` re-sends only when a call's classification
  changes. So a call that was already Business stayed unlinked in the CRM:
  - one the user marked Business by hand, uploaded, then used **Create lead** on;
  - a work-SIM call that uploaded before the lookup answered.

  `ReclassifyNumberUseCase` now compares each call's CRM link (type + id; a rename doesn't count) before and
  after. A Business call whose link changed and that already has an activity is re-queued. Its `updatedAt` moves
  past the previous one, so the CRM answers UPDATED, not CONFLICT.
- **A new call within 24 h of a "not in CRM" answer wasn't searched.** `numbersNeedingCrmLookup` now also
  re-asks when the cached answer is older than the end of one of the number's calls. Each completed call is
  searched once; the 24 h TTL still covers numbers with no new calls.
- **Tests:** 4 new (create lead re-links an uploaded call; late CRM match re-sends a work-SIM call; a renamed
  record doesn't re-send; a new call is searched despite a fresh cache) plus the DAO query test. 139 JVM tests,
  lint 0/0.
- **On the A50:** a fake call-log row from an unknown number was marked Business. Its note uploaded with no
  lead (version 2). After **Create lead**, the same activity (same `clientId`, note kept) became version 3 with
  `leadId = lead_00004`. Still one activity for the number, and the number appeared in no app log line.

---

## Look and feel redesign (2026-10-10)
After the user's 10-screen mockup (comparison table: [FLOW_REVIEW.md](FLOW_REVIEW.md#design-mockup-vs-the-app)).
- **Theme:** blue app bar drawn behind the status bar, with light status icons. Colour tokens are in
  `values/colors.xml` and `values-night/colors.xml` (one DayNight theme). There are styles for 10dp buttons, 8dp fields,
  12dp cards, segmented tabs and pills. Lift-on-scroll is off so the bar stays blue.
- **Shared visuals:** `ui/common/CallVisuals` has initials avatars (person icon for numbers and private calls), call
  type, classification and sync pills, and the "Lead · Company" line. `Formatters.dayTime` gives "Today, 10:24".
- **Navigation:** a side menu (on the list only) opens Sync queue, Number rules, Settings and Why this approach?.
  `AppNavigator` gained `openSyncQueue`, `openAbout` and `showCallList`, plus `openCallDetail(id, askClassification)`.
- **New screens:** `ClassifyCallDialogFragment` (a "Work call?" notification tap now opens the call with this dialog,
  via `MainActivity.ACTION_CLASSIFY_CALL`); `CallSavedDialogFragment` after Save, replacing the snackbar;
  `SyncQueueFragment` + `SyncQueueViewModel`; `AboutFragment`. Create lead is now full screen. Both full-screen
  dialogs extend `ui/common/FullScreenDialogFragment`, which handles the edge-to-edge insets itself.
- **Wording:** the UI says "Professional" for `Classification.BUSINESS`. Code, the outbox and the CRM keep BUSINESS.
- **Not built:** mockup screens 3 (manual match) and 7 (lead detail), and the extra settings toggles and lead fields.
  They need CRM API changes.
- **Checked:** 144 JVM tests (5 new in `SyncQueueViewModelTest`), lint 0/0 (one file-scoped exception in
  `app/lint.xml` for the drawer header). On the A50, light and dark: list, side menu, details, classify (selection
  updated the CRM), save → saved screen → "View in activities", create lead (empty-name error; the lead created in the
  CRM and the call relinked), sync queue tabs, settings, about.

---

## Assignment §2 and §3 follow-up (2026-10-10)
A review of spec §2 (CRM matching) and §3 (professional vs personal) against the app. The full table is in
[FLOW_REVIEW.md](FLOW_REVIEW.md#assignment-2-and-3-review-2026-10-10).
- **F1, prompt cap:** `CrmLookupWorker.promptForUndecided` now keeps at most `MAX_PROMPTS` (3) "Work call?" prompts
  *showing*, across runs; the newest calls keep theirs and an older prompt is cancelled. Prompts carry an extra so
  `PostCallNotifier.shownPromptCallIds` can tell them apart from "Add notes" notifications, which share the tag.
- **F2, re-link:** `UploadPendingActivitiesUseCase.healStaleCrmLink` ends with `CrmLookupRequester.requestLookup()`
  (implemented by `CrmLookupScheduler`, the network-bound run), so a record that moved to a new id re-links at once.
- **Professional → log form:** the notification's Professional button is now an activity intent to `PostCallActivity`
  (Android 12+ won't start an activity from a receiver that a notification button triggered). The form cancels the
  notification, marks the call professional, and waits for that before showing. In the Classify dialog, the call's
  screen scrolls to the log form.
- **Create lead in the after-call form**, for unknown numbers, reusing `CreateLeadDialogFragment` and the shared
  `CallDetailViewModel`.
- **No Create lead on Personal calls** (`CallDetailUiState.canCreateLead`): it would mark the call professional and
  upload it. The CRM card says why instead.
- **No prompt for missed/rejected calls** (`PostCallPromptPolicy`): nothing to log; they wait under Unsorted.
- **Docs:** DECISIONS.md §1 rewritten ("Professional", every way to classify, why the CRM is searched first).
  Debug seeder: `bulk --es type incoming`.
- **Checked:** 149 JVM tests (5 new), lint 0/0. On the A50: F1 (6 unknown incoming calls over 3 sweeps → 2, 3, 3
  prompts showing); F2 (lead moved to a new id → lookup 0.3 s after the heal, 11 activities re-sent with the new lead
  30 s later, the note kept); a Personal call shows no Create lead.

---

## Product owner's call flow: classify first (2026-10-10)
The product owner's 8-step flow, implemented (comparison table in
[FLOW_REVIEW.md](FLOW_REVIEW.md#call-flow-from-the-product-owner-2026-10-10-implemented)).
- **Classification first** (`ClassifyCallUseCase`): the number's saved answer (rule) → work SIM → Unsorted. The CRM match
  and saved phone contacts no longer decide (`ClassificationSignals.isDeviceContact` removed; `crmMatch` only links).
- **Asked once per number:** every answer is saved as a rule. The notification's Personal (`PostCallActionReceiver`) and
  Professional (`PostCallActivity`) buttons and Log (`MainViewModel`) pass `applyToNumber = true`; the Classify dialog and
  the call screen tick "Remember for this number". Create lead saves a BUSINESS rule. The Rules screen ("Saved for this
  number") lists them all.
- **CRM only for professional calls:** `CallDao.numbersNeedingCrmLookup` adds `classification = 'BUSINESS'`. A BUSINESS
  answer (call, rule, work SIM) calls `CrmLookupRequester.requestLookup()` so the link comes at once.
- **Auto-create the lead** (`ResolveCrmMatchesUseCase`): on "not found", if `SettingsRepository.autoCreateLeads()` (new
  switch under Settings → CRM, default on) and a BUSINESS call of the number has a phonebook name →
  `CreateLeadFromCallUseCase` with that name. Offline → the "not found" is forgotten so the next run retries. The worker
  logs counts only ("created N leads").
- **DB v3** (`MIGRATION_2_3`, data only, schema `3.json`): numbers the CRM made BUSINESS or a saved contact made PERSONAL
  become saved answers (a user's own rule wins), and those calls' source becomes `USER_RULE`, so nothing flips on
  upgrade. `MigrationTest.migrate2To3…` covers it.
- **Debug seeder:** `bulk --es type incoming --es name N`.
- **Checked:** 153 JVM tests (updated for the new order, plus new ones: an answer is remembered; a BUSINESS answer
  requests a lookup; unsorted/saved-contact numbers are never looked up; auto-lead with/without name, switch, offline
  retry; the settings switch; an answer in the app dismisses the number's open prompts). 154 JVM tests, lint 0/0.
- **On the A50:** installed over the v2 build, classifications kept ("Saved for this number"). (1) Unknown number →
  asked → Personal → its next call Personal without asking, 0 CRM lookups. (2) A CRM lead's number → asked once, 0
  lookups before the answer → Professional → looked up at once, linked to `lead_00005`, uploaded with it; next call
  professional without asking, linked, uploaded. (3) A phonebook contact "Anita Desai" not in the CRM → asked →
  Professional → lead `lead_00006` "Anita Desai" created automatically, call uploaded linked; its prompt disappeared.
  `connectedDebugAndroidTest`: 3/3, including `migrate2To3…`.
- **Fixed during the device run:** answering in the app left the number's "Work call?" notification up; any answer now
  dismisses them (`CallPromptDismisser`, implemented by `PostCallNotifier`). The debug seeder no longer crashes the app
  when a reinstall drops its WRITE_CALL_LOG grant.
- **Home screen toolbar (later the same day):** the ⋮ menu (only "Upload now") and the upload-status filter icon are
  removed. Both duplicated the Sync queue screen (its Pending / Failed / Synced tabs and upload icon), which the
  home screen's "waiting to upload" banner and the side menu open. `CallListViewModel` lost its sync filter and
  `uploadNow()`; the unused menus, filter icon, accent colour and strings went too. 154 tests, lint 0/0; checked on
  the A50.
