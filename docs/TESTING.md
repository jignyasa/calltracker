# Testing approach

The risky parts of this app are **privacy** (a personal call must never leave the phone) and **reliability** (no call or
note may be lost or duplicated). The architecture keeps all of that logic in pure-Kotlin use cases, so it can be tested
quickly and exhaustively on the JVM; Android-specific pieces are tested with Robolectric, on a device, or both.

```
              ┌───────────────────────────────┐
              │ Manual device runs (Samsung A50,│  real calls, reboot, airplane mode, crashes,
              │ Android 11) against the mock CRM│  OEM battery screens, release APK
              ├───────────────────────────────┤
              │ On-device instrumented (2)     │  SQLCipher encryption, Room migration
              ├───────────────────────────────┤
              │ Robolectric (DB, workers, UI)  │  real Room SQL, WorkManager workers, notifications
              ├───────────────────────────────┤
              │ JVM unit tests                 │  use cases, ViewModels (Turbine), network (MockWebServer)
              └───────────────────────────────┘
```

## Running the tests

```bash
./gradlew testDebugUnitTest                                   # 130 JVM + Robolectric tests
./gradlew testDebugUnitTest --tests "*.SyncGateTest"          # one class
./gradlew connectedDebugAndroidTest                           # on-device (ANDROID_SERIAL=<id> to pick one)
./gradlew lintDebug                                           # warnings fail the build
cd mock-server && npm test                                    # 16 mock API tests
```

Robolectric tests use `@Config(sdk = [34])` (Robolectric needs JDK 21 for newer SDKs). SQLCipher can't load under
Robolectric, so encryption is covered by the on-device `EncryptedDatabaseTest`.

## What the automated tests cover (130 JVM tests)

| Area | Tests | Examples |
|---|---|---|
| Classification | `ClassifyCallUseCaseTest` (6), `ResolveCrmMatchesUseCaseTest` (10), `SetCallClassificationUseCaseTest` (7) | precedence of rule → CRM → work SIM → contact; a hand-picked call is never overridden; numbers marked personal are never looked up; a number removed from the CRM is dequeued |
| Privacy | `SyncGateTest` (6), `UploadPendingActivitiesUseCaseTest` (16), `LogHygieneTest` | only BUSINESS reaches the outbox; a non-business row injected into the outbox is still never sent; the payload has no contact name; no log line can print call data |
| Detection | `SweepCallLogUseCaseTest` (11), `CallTypeMappingTest`, `LibPhoneNumberNormalizerTest` (5) | 7-day backfill floor (incl. the device-found regression), late-committed rows, log wipes, paging, idempotent re-sweeps |
| Sync | `UploadPendingActivitiesUseCaseTest` (16), `SyncWorkerTest` (5), `ActivityUploaderImplTest` (3, MockWebServer) | partial failure, 5xx/IO retry, duplicate replay, edit during upload, conflict (incl. skewed clock), crash recovery, stale CRM link, 250-item backlog in 100/100/50 |
| CRM | `CrmRepositoryImplTest` (6, MockWebServer), `CreateLeadFromCallUseCaseTest` (6), `CrmLookupWorkerTest` (4) | 200/400/409/422/5xx/dropped connection; idempotent lead creation; prompts only for undecided calls; offline still prompts |
| Database | `DatabaseTest` (7, Robolectric + real Room SQL) | unique CallLog rows, cascades, outbox claim + generation guard, crash reset, lookup query, paged list filters and counts |
| UI | `CallListViewModelTest` (7, Paging `asSnapshot`), `CallDetailViewModelTest` (10), `RulesViewModelTest` (4), `SettingsViewModelTest` (4), `PostCallPromptPolicyTest` (2), `OemBatteryGuideTest` (4) | filters survive process death; the form never overwrites typing; save shows the cleaned values; create lead; rules reclassify; work SIM re-applies |

Test doubles: `testing/FakeRepositories` implements every domain repository over one shared in-memory state (like the
real database); `testing/TestUseCases` wires all use cases over it; `testing/MainDispatcherRule` runs `viewModelScope` on
a test dispatcher.

**On-device instrumented:** `EncryptedDatabaseTest` (the file has no SQLite header and no readable data, and reopens with
the Keystore-wrapped key) and `MigrationTest` (v1 → v2 schema and data).

## Device testing

All on a **Samsung Galaxy A50 (Android 11, dual SIM)** against the mock CRM, with a **debug-only CallLog seeder**
(`app/src/debug/.../CallLogSeedReceiver`, triggered only from adb) to create incoming/missed/rejected/blocked/private
calls and large backlogs, and fault injection on the mock server (`X-Mock-Fault`, `/__admin/faults`).

| Area | Verified |
|---|---|
| Detection | real outgoing calls picked up in ~3 s; with the app killed (broadcast restarts it); force-stopped (caught up on open); CallLog change without a call (content trigger); reboot; app update |
| Robustness | 450-row backlog; app crashed mid-sweep → exactly the remainder imported; corrupted DB key → new key, DB rebuilt, calls re-imported |
| Classification & CRM | CRM match upgrades a saved contact to Business; unknown → prompt (max 3); Business / Personal / Log from the notification; offline prompt resolved when the CRM came back |
| UI | filters, detail form, rotation with unsaved notes, discard dialog, create lead, rules, work SIM (real SIMs), titles |
| Sync | upload, UPDATED, 503 backoff, item ERROR, conflict, stale CRM link, clock skew; **airplane mode** (held by the network constraint, then uploaded by itself); **crash during an in-flight 100-item batch** (exactly 150 stored, no duplicates); **wrong token** (401 → kept → uploaded with the right build) |
| Release APK | R8 build imported, classified and uploaded a 65-call backlog in 7 s; 10 unknown calls never sent; no app/network logs; paged list under load; Samsung battery prompt and screen |
| Privacy | the CRM only ever received Business test calls — none of the phone's real calls; DB file has no plaintext |

Full logs of each run: [TEST_REPORT_PHASE3.md](TEST_REPORT_PHASE3.md) and the "Checked" sections of
[IMPLEMENTATION_NOTES.md](IMPLEMENTATION_NOTES.md). Bugs found on the device were fixed and given a regression test where
possible.

## Not covered

- Real incoming calls from another phone (no second phone available; the CallLog rows they produce were simulated).
- Phone-maker battery screens other than Samsung.
- Espresso UI tests (manual runs instead).
