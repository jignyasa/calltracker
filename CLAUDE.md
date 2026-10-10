# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project
Android take-home (spec: `docs/assignment.pdf`). Real-time call activity capture → CRM logging,
with strict privacy (personal calls never leave the device) and reliable offline-first sync.
Build: AGP 9.2.1 with **built-in Kotlin** (no `kotlin-android` plugin applied in `:app`; the root applies it with
`apply false` only to pin KGP 2.4.21), KSP for Hilt/Room codegen, compileSdk 37, targetSdk 36, minSdk 26, Java 17.
Requires SDK build-tools 36.0.0 + platform `android-37.0`. The Gradle daemon runs on JDK 17 (`gradle/gradle-daemon-jvm.properties`).
Namespace/appId `com.example.calltracker`.
WorkManager's default initializer is removed in the manifest; `CallTrackerApp` provides the Hilt worker factory.
Phase plan + progress: `docs/PLAN.md`. Don't write code outside the current phase unless asked.
What each finished phase built and how it was verified: `docs/IMPLEMENTATION_NOTES.md` (add a section per phase).
Reviewer-facing docs: `README.md`, `docs/ARCHITECTURE.md`, `docs/DECISIONS.md`, `docs/TESTING.md` — keep them in step with the code.
Release APKs for delivery live in `dist/` (rebuild with `assembleRelease`, with and without `-PcrmBaseUrl=http://localhost:3000/`).

## Stack (do not substitute without asking)
- **Language:** Kotlin only. **UI:** traditional XML layouts + ViewBinding (no Compose), Material Components.
- **Architecture:** MVVM + Clean Architecture, single `:app` module, packages:
  - `ui/`: Activities/Fragments, ViewModels, adapters. ViewModels expose `StateFlow<UiState>`; one-off events via `Channel`/`SharedFlow`.
    Single activity; screens are fragments pushed through `AppNavigator` (`MainActivity`, plain transactions, no nav library).
    Each screen sets its title with `requireActivity().setTitle(...)` (the toolbar has no `app:title`, which would block it).
    Dialogs that return data are `DialogFragment`s + Fragment Result API (survive rotation). Forms keep their draft in the
    ViewModel and refill fields only when `formVersion` changes, so the user's typing is never overwritten.
    ViewModels depend on interfaces (e.g. `SweepRequester`, `SimCardSource`), never on Android classes, so they test on the JVM.
    Look and feel follows `docs/images/design-mockup.png`: colours only as tokens in `values/` + `values-night/colors.xml`,
    avatars/pills via `ui/common/CallVisuals`, full-screen dialogs extend `ui/common/FullScreenDialogFragment`.
    UI text says "Professional" for `Classification.BUSINESS` (code, outbox and CRM keep BUSINESS).
  - `domain/`: pure Kotlin. Models, repository **interfaces**, use cases. No `android.*` imports.
  - `data/`: Room DB, DAOs, entities, Retrofit APIs, DTOs, mappers, repository implementations, CallLog reader.
  - `sync/`: WorkManager workers + scheduling · `telephony/`: receivers + call detection · `di/`: Hilt modules.
- **DI:** Hilt everywhere (`@HiltAndroidApp`, `@AndroidEntryPoint`, `@HiltViewModel`, `@HiltWorker` + `HiltWorkerFactory`).
- **Persistence:** Room is the **single source of truth**. UI only observes Room Flows; network results are written to Room, never shown directly.
  Room is encrypted with **SQLCipher** (`SupportOpenHelperFactory`); the passphrase is generated once and wrapped by an Android Keystore key.
- **Async:** Coroutines + Flow everywhere. No RxJava, no `GlobalScope`, no `runBlocking` outside tests.
  Inject dispatchers (`@IoDispatcher` etc.) so tests can swap them.
- **Background:** `CoroutineWorker` for all sync/sweep work. No long-running Services for sync.
- **Network:** Retrofit + OkHttp + kotlinx.serialization converter. Logging interceptor only in debug builds.
- **Phone numbers:** libphonenumber. Normalize to **E.164**, default region **IN**. Match/store on the normalized number; keep the raw number for display.

## Call detection
1. `PHONE_STATE` BroadcastReceiver is **only a trigger**. It reads no call data. On `IDLE` it enqueues a one-shot sweep (short delay so CallLog commits).
2. **CallLog is the source of truth** for number, type, start, duration, SIM (`PHONE_ACCOUNT_ID` → slot via subscription id, ICCID,
   or on API 30+ Telecom phone accounts; Samsung stores the ICCID, which apps can't read on Android 10+).
   Types: INCOMING, OUTGOING, MISSED, REJECTED (BLOCKED/VOICEMAIL handled explicitly). End time = start + duration.
3. **Watermark-based catch-up sweep**: persist the last processed `CallLog._ID`/`DATE`; each sweep reads rows past the watermark,
   upserts them (deduped on CallLog id + date), and advances the watermark in the same transaction.
   Runs on `BOOT_COMPLETED`/`MY_PACKAGE_REPLACED`, app open, PHONE_STATE idle, a WorkManager **content-URI trigger on
   `CallLog.Calls.CONTENT_URI`** (`CallLogChangedWorker`, re-armed by every sweep), and an hourly unique periodic worker.
   All of them only call `SweepScheduler`. The logic lives in `domain/usecase/SweepCallLogUseCase` (pages of 200, one transaction per page).
4. First sweep backfills **7 days** (`sweep_state.not_before` keeps that floor for every later sweep). Saved phone contacts
   (`CACHED_NAME`) are **not** auto-Personal: like any number with no saved answer, they're asked once.
5. **Classify first, CRM only for professional calls** (the product owner's call flow; see `docs/FLOW_REVIEW.md`). The sweep
   classifies from local signals (`GatherClassificationSignals`: the number's saved answer = rule, work SIM; the *cached* CRM record
   only links). Every user answer is saved as a number rule (`applyToNumber = true` everywhere; "Remember for this number" ticked),
   so a number is asked once. Then `CrmLookupScheduler.resolveAfterSweep` runs `CrmLookupWorker` →
   `ResolveCrmMatchesUseCase` (**BUSINESS** numbers from the last 7 days with no/stale cache, 24 h TTL, or a cached answer older than
   one of the number's calls ended; ≤50 per run; a BUSINESS answer also calls `CrmLookupRequester` at once). Not found → auto-create
   the lead from the phonebook name if `SettingsRepository.autoCreateLeads()` (default on), else Create lead is offered → `ReclassifyNumberUseCase`
   (skips USER_MANUAL, reconciles the outbox; a BUSINESS call whose CRM link (type + id) changed is re-sent with a bumped
   `updatedAt`, so links found or created after upload reach the CRM). Only after that does it post the post-call prompt (`PostCallPromptPolicy`: UNCLASSIFIED,
   incoming/outgoing only (no prompt for missed/rejected), ended < 1 h ago, never more than 3 *showing*: the newest calls keep
   theirs, counted with `PostCallNotifier.shownPromptCallIds`). The prompt's Professional button opens `PostCallActivity`
   (it marks the call, then shows the log form with Create lead for unknown numbers). The post-sweep run has no network constraint so prompts aren't delayed offline; failed lookups go to the
   network-bound `crm-lookup-retry` work.
6. A freshly installed app gets no broadcasts until it's opened once (Android "stopped state"). When testing on a device, launch it first.
   On the emulator: `adb emu gsm call <n>` / `gsm accept <n>` / `gsm cancel <n>`; outgoing calls via `adb shell am start -a android.intent.action.CALL -d tel:<n>`.

## Privacy (hard rule)
- Every call is classified `BUSINESS`, `PERSONAL`, or `UNCLASSIFIED`.
  Precedence: the number's saved answer (rule) → work-SIM setting (BUSINESS) → UNCLASSIFIED (ask once via post-call notification;
  the answer is saved). The CRM never decides classification. Personal and unsorted numbers are never sent to the CRM, not even as a lookup.
- **Only `BUSINESS` calls may ever be inserted into the sync outbox.** `domain/usecase/SyncGate.reconcile()` is the *only* caller of
  `OutboxRepository.enqueue`. Anything that changes a call's classification or activity must call `reconcile` inside the same
  `TransactionRunner.run {}` block. PERSONAL/UNCLASSIFIED data never goes into any network DTO, log line, or analytics.
- Reclassifying BUSINESS → PERSONAL before upload removes the pending outbox item.
- Every BUSINESS call gets an activity (with its client UUID) and is queued immediately; notes added later re-arm the same item.
- A per-number rule never overrides a call the user classified by hand (`ClassificationSource.USER_MANUAL`).
- Never log raw phone numbers or notes (mask them). `android:allowBackup="false"`; DB excluded from data extraction.
  The debug HTTP log is BASIC level with `redactQueryParams("phone")`; never raise it to BODY.
- CRM lookups send only the E.164 number, and only for numbers with a BUSINESS call. A number with a PERSONAL rule, or with any
  call the user marked PERSONAL by hand, is never looked up (`CallDao.numbersNeedingCrmLookup`). Auto-create lead sends the phonebook
  name, only for a professional number that isn't in the CRM, and only with the setting on.

## Sync (outbox pattern)
- `outbox` rows (one per activity, keyed by `call_id`): `clientId` (UUID generated once locally, **sent as `Idempotency-Key`**), status
  (PENDING / IN_FLIGHT / SYNCED / FAILED_RETRYABLE / FAILED_PERMANENT), `generation`, attempt count, last error, timestamps.
- `generation` is bumped on every (re)enqueue. A worker marks an item SYNCED only if the generation it sent is still current,
  so an edit made during an upload is never lost.
- Batch upload; the server returns **per-item results**. Each item is updated on its own; one bad item never blocks the batch.
- **Exponential backoff** (`BackoffPolicy.EXPONENTIAL`), constraint `NetworkType.CONNECTED`.
  4xx validation → permanent (surfaced in UI). 5xx/IO → retry. 409 → server id wins, notes merged by last-write timestamp.
- Always use **unique work** (`enqueueUniqueWork` / `enqueueUniquePeriodicWork`).
- When a worker starts, reset stale `IN_FLIGHT` rows to PENDING (crash recovery).
- Implementation: `SyncWorker` → `domain/usecase/UploadPendingActivitiesUseCase` (claims ≤100 via `OutboxRepository.claimBatch`,
  every outbox update after sending is generation-guarded; re-checks BUSINESS before building `ActivityPayload`, which holds only
  contract fields). `OutboxRepositoryImpl.enqueue` calls `SyncRequester.requestSync()` (5 s debounce so the enqueuing transaction
  commits first; `APPEND_OR_REPLACE`, `CONNECTED`, exponential backoff); hourly periodic + a run on every app start.
- Stale CRM link (INVALID "leadId/contactId does not exist") is healed, not failed: forget cache → reclassify number → re-queue if
  still BUSINESS → request a CRM lookup at once (`CrmLookupRequester`), so a record under a new id re-links. A log save is stamped `max(now, previous updatedAt + 1)` so a CRM copy with a skewed-ahead clock can't swallow edits.
- Unsupported call types are mapped for the CRM: BLOCKED→REJECTED, VOICEMAIL→MISSED, OTHER→INCOMING. A BUSINESS call without a
  number fails locally (FAILED_PERMANENT) and is never sent.

## Mock CRM server
- `/mock-server` (Node + Express 5, CommonJS, no deps beyond express; tests use `node:test`).
  `src/app.js` holds all routes; `mock-server/README.md` is the **API contract**. Keep app DTOs in sync with it.
- `/api/v1` with `Authorization: Bearer dev-token`: `GET /contacts/lookup?phone=`, `POST /leads` (Idempotency-Key header),
  `POST /activities/batch` (≤100 items; per-item status CREATED/DUPLICATE/UPDATED/CONFLICT/INVALID/ERROR; `clientId` is the item's idempotency key).
  All times are epoch millis.
- Faults: `X-Mock-Fault` header or `POST /__admin/faults` (latency, failureRate, itemFailureRate, forceStatus/forceCount).
- App base URL comes from `BuildConfig.CRM_BASE_URL` (`http://10.0.2.2:3000/` on the emulator; physical device: `adb reverse tcp:3000 tcp:3000`
  and build with `-PcrmBaseUrl=http://localhost:3000/`). Token: `BuildConfig.CRM_API_TOKEN` (`-PcrmApiToken`, default `dev-token`).

## Conventions
- Every dependency goes through the version catalog. Check current APIs/versions with Context7 before adding or upgrading one.
- Tests: JUnit4, MockK/fakes, Turbine, `kotlinx-coroutines-test`, MockWebServer for API/sync, `TestListenableWorkerBuilder` for workers.
  Keep `domain/` JVM-testable. `test/.../testing/FakeRepositories` implements every domain repository over shared in-memory state;
  `testing/TestUseCases` wires every use case over it; ViewModel tests use `testing/MainDispatcherRule` (create the ViewModel inside
  the test, not in a property initializer, or `viewModelScope` is built before the test Main dispatcher is installed).
  Rule/classification changes go through `CallReclassifier` (never overrides USER_MANUAL; reconciles the outbox).
- DAO tests run on Robolectric with an unencrypted in-memory Room DB and **`@Config(sdk = [34])`** (Robolectric needs JDK 21 for SDK 35+).
  SQLCipher can't load under Robolectric; encryption is covered by `androidTest/.../EncryptedDatabaseTest`.
- Room schemas are exported to `app/schemas/`. Bump the DB version and add a migration (`data/local/Migrations.kt`, `ALL_MIGRATIONS`)
  for any schema change, and cover it in `androidTest/.../MigrationTest` (on-device: Robolectric doesn't see the schema assets).
- `DatabaseKeyProvider`: if the Keystore-wrapped key can't be unwrapped, a new key is made and the DB is deleted
  (calls are re-read from the CallLog).

## Hardening rules (Phase 7)
- Lint runs with `warningsAsErrors`; exceptions live in `app/lint.xml`, each with its reason. Keep it at 0/0.
- Release: R8 + resource shrinking (`app/proguard-rules.pro`; Log.v/d/i stripped). Signed with the key from Gradle properties
  `releaseStoreFile/releaseStorePassword/releaseKeyAlias/releaseKeyPassword`, else the debug key. Never commit a keystore.
- The call list is paged by Room (`CallDao.pagedList`, filters in SQL); never load all calls into memory for the UI.
- Logs: counts, HTTP codes and exception *types* only; `LogHygieneTest` enforces it.
- Battery: `ui/common/BackgroundRestrictions` + `ui/settings/OemBatteryGuide` (packages mirrored in the manifest `<queries>`,
  checked by `OemBatteryGuideTest`).

## Commands (Windows: `gradlew.bat`, or `./gradlew` in Git Bash)
- Build: `./gradlew assembleDebug` · Release APK: `./gradlew assembleRelease` (device: `-PcrmBaseUrl=http://localhost:3000/`)
- Unit tests: `./gradlew testDebugUnitTest`
- Single test: `./gradlew testDebugUnitTest --tests "com.example.calltracker.domain.usecase.SyncGateTest"`
- Instrumented tests: `./gradlew connectedDebugAndroidTest` (pick a device with `ANDROID_SERIAL=emulator-5554`; AVD with an installed image: `Pixel_7_API_31`)
- Lint: `./gradlew lintDebug`
- Mock server: `cd mock-server && npm install && npm start` (port 3000) · tests: `npm test` · single test: `node --test --test-name-pattern="DUPLICATE" "test/**/*.test.js"`
