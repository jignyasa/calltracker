# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project
Android take-home (spec: `docs/assignment.pdf`). Real-time call activity capture → CRM logging,
with strict privacy (personal calls never leave the device) and reliable offline-first sync.
Build: AGP 9.2.1 with **built-in Kotlin** (no `kotlin-android` plugin applied in `:app`; the root applies it with
`apply false` only to pin KGP 2.4.21), KSP for Hilt/Room codegen, compileSdk 37, targetSdk 36, minSdk 26, Java 17.
Requires SDK build-tools 36.0.0 + platform `android-37.0`. Namespace/appId `com.example.calltracker`.
WorkManager's default initializer is removed in the manifest; `CallTrackerApp` provides the Hilt worker factory.
Phase plan + progress: `docs/PLAN.md`. Don't write code outside the current phase unless asked.

## Stack (do not substitute without asking)
- **Language:** Kotlin only. **UI:** traditional XML layouts + ViewBinding (no Compose), Material Components.
- **Architecture:** MVVM + Clean Architecture, single `:app` module, packages:
  - `ui/`: Activities/Fragments, ViewModels, adapters. ViewModels expose `StateFlow<UiState>`; one-off events via `Channel`/`SharedFlow`.
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
2. **CallLog is the source of truth** for number, type, start, duration, SIM (`PHONE_ACCOUNT_ID`).
   Types: INCOMING, OUTGOING, MISSED, REJECTED (BLOCKED/VOICEMAIL handled explicitly). End time = start + duration.
3. **Watermark-based catch-up sweep**: persist the last processed `CallLog._ID`/`DATE`; each sweep reads rows past the watermark,
   upserts them (deduped on CallLog id + date), and advances the watermark in the same transaction.
   Runs on `BOOT_COMPLETED`, app open, PHONE_STATE idle, and a unique periodic worker, so a missed broadcast (OEM kill, Doze) never loses a call.

## Privacy (hard rule)
- Every call is classified `BUSINESS`, `PERSONAL`, or `UNCLASSIFIED`.
  Precedence: user per-number rule → CRM match (BUSINESS) → work-SIM setting → device contact not in CRM (PERSONAL) → UNCLASSIFIED (ask via post-call notification).
- **Only `BUSINESS` calls may ever be inserted into the sync outbox.** Enforce it in one place (the enqueue use case) and cover it with tests.
  PERSONAL/UNCLASSIFIED data never goes into any network DTO, log line, or analytics.
- Reclassifying BUSINESS → PERSONAL before upload removes the pending outbox item.
- Never log raw phone numbers or notes (mask them). `android:allowBackup="false"`; DB excluded from data extraction.

## Sync (outbox pattern)
- `outbox` rows: `clientId` (UUID generated once locally, **sent as `Idempotency-Key`**), status
  (PENDING / IN_FLIGHT / SYNCED / FAILED_RETRYABLE / FAILED_PERMANENT), attempt count, last error, timestamps.
- Batch upload; the server returns **per-item results**. Each item is updated on its own; one bad item never blocks the batch.
- **Exponential backoff** (`BackoffPolicy.EXPONENTIAL`), constraint `NetworkType.CONNECTED`.
  4xx validation → permanent (surfaced in UI). 5xx/IO → retry. 409 → server id wins, notes merged by last-write timestamp.
- Always use **unique work** (`enqueueUniqueWork` / `enqueueUniquePeriodicWork`).
- When a worker starts, reset stale `IN_FLIGHT` rows to PENDING (crash recovery).

## Mock CRM server
- `/mock-server` (Node + Express, in-memory + JSON file persistence).
- Endpoints: lead/contact lookup by phone, create lead, batch-upload activities (honours `Idempotency-Key`, returns per-item results).
- Fault injection (latency, random 5xx, forced 409) via query/env flags.
- App base URL comes from `BuildConfig` (`http://10.0.2.2:3000/` for the emulator).

## Conventions
- Every dependency goes through the version catalog. Check current APIs/versions with Context7 before adding or upgrading one.
- Tests: JUnit4, MockK/fakes, Turbine, `kotlinx-coroutines-test`, in-memory Room for DAOs, MockWebServer for API/sync,
  `TestListenableWorkerBuilder` for workers. Keep `domain/` JVM-testable.

## Commands (Windows: `gradlew.bat`, or `./gradlew` in Git Bash)
- Build: `./gradlew assembleDebug` · Release APK: `./gradlew assembleRelease`
- Unit tests: `./gradlew testDebugUnitTest`
- Single test: `./gradlew testDebugUnitTest --tests "com.example.calltracker.domain.SomeTest.someMethod"`
- Instrumented tests: `./gradlew connectedDebugAndroidTest` · Lint: `./gradlew lintDebug`
- Mock server: `cd mock-server && npm install && npm start` (port 3000)
