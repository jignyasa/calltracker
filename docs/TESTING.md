# Testing

The two things that matter most in this app are:
- **Privacy** — a personal call must never leave the phone.
- **Reliability** — no call or note may be lost or sent twice.

All of that logic is plain Kotlin (no Android code), so it can be tested quickly and thoroughly on a computer. The
Android parts are tested with Robolectric (Android simulated on the computer), on a real phone, or both.

```
              ┌────────────────────────────────┐
              │ By hand on a real phone        │  real calls, restart, airplane mode, crashes,
              │ (Samsung A50, Android 11)      │  battery screens, release APK
              ├────────────────────────────────┤
              │ On-device automated tests (2)  │  database encryption, database upgrade
              ├────────────────────────────────┤
              │ Robolectric                    │  real database queries, background jobs, notifications
              ├────────────────────────────────┤
              │ Plain unit tests               │  business rules, screens' logic, network calls
              └────────────────────────────────┘
```

## How to run the tests

```bash
./gradlew testDebugUnitTest                                   # all 154 computer tests
./gradlew testDebugUnitTest --tests "*.SyncGateTest"          # one test class
./gradlew connectedDebugAndroidTest                           # on a phone/emulator (ANDROID_SERIAL=<id> to pick one)
./gradlew lintDebug                                           # code checks; any warning fails
cd mock-server && npm test                                    # 16 tests for the mock CRM
```

Robolectric tests use `@Config(sdk = [34])`, because newer Android versions need JDK 21 in Robolectric. The encrypted
database can't run under Robolectric, so encryption is tested on a real device (`EncryptedDatabaseTest`).

## Step-by-step test plan (try it yourself)

Ten short checks that walk through the whole call flow on an emulator or a phone.

**Before you start**
- Start the mock CRM: `cd mock-server && npm install && npm start` (port 3000).
- Emulator: build and install as usual. Phone: run `adb reverse tcp:3000 tcp:3000` and build with
  `-PcrmBaseUrl=http://localhost:3000/`.
- Install the app, **open it once** and grant the permissions (Android sends no call events to an app that was never opened).
- Leave **Settings → Create leads automatically** on (the default); step 3 needs it.
- Step 4 needs a second phone (or on the emulator: `adb emu gsm call <number>`); step 7 needs a phone with two SIMs.
- To see what the CRM received, open `http://localhost:3000/__admin/state` in a browser (everything stored).

**Starting again from a clean state**

The mock server saves its data to `mock-server/data/db.json`, so restarting it keeps everything. To clear it:
- **While it's running:** `curl -X POST http://localhost:3000/__admin/reset` (PowerShell:
  `Invoke-RestMethod -Method Post http://localhost:3000/__admin/reset`). It answers `{"ok":true}`; only the sample leads
  and contacts remain.
- **Or:** stop the server, delete `mock-server/data/db.json`, and run `npm start` again.
- **Or keep nothing at all:** start it with `DATA_FILE=memory npm start` (PowerShell: `$env:DATA_FILE='memory'; npm start`);
  everything is cleared when it stops.

Clearing the server doesn't clear the app, which still remembers its uploads and saved answers. Also run
`adb shell pm clear com.example.calltracker` (or reinstall), then open the app once and grant the permissions again.

| # | Test | Starts as | Do | Expect |
|---|---|---|---|---|
| 01 | First 7 days come in | Unsorted | Just open the app after installing. | The last 7 days of calls appear under Unsorted. No notifications for old calls. The CRM still has 0 activities. |
| 02 | Personal stays private | Personal | Open an Unsorted call and choose Personal. | Every call from that number turns Personal. Nothing changes in the CRM: no lookup, no upload. |
| 03 | Professional + automatic lead | Professional | Open an Unsorted call from a saved phone contact (a number with no saved answer yet) and choose Professional. | The Upload queue shows Synced. The CRM gets a new lead with the phonebook name, plus activities for all of that number's calls. |
| 04 | Asked once on a live call | Prompt | A friend whose number has no answer yet calls you. Talk, then hang up. | Within seconds a notification asks "Professional or Personal?". Tap Professional, fill in the outcome and notes, save. |
| 05 | Never asked again | Professional | The same friend calls again. | No notification. The call is Professional automatically and uploads. |
| 06 | Missed call waits quietly | Unsorted | Let a call from a new number ring out. | No notification. It waits under Unsorted. |
| 07 | Work SIM | Professional | With two SIMs: Settings → choose SIM 2 as the work SIM. | SIM 2 calls from the last 7 days that you didn't classify by hand become Professional and upload. |
| 08 | Offline, then back online | — | Turn on airplane mode, mark a call Professional, then turn airplane mode off. | The Upload queue shows it waiting, then Synced once the phone is online. |
| 09 | CRM server down | — | Stop `npm start`, make a Professional call, start the server again. | It retries, waiting longer each time, and syncs later. No crash, nothing lost. |
| 10 | Undo before upload | Personal | Offline: mark a call Professional, then switch it to Personal. Go online. | Its item disappears from the Upload queue and never reaches the CRM. |

The same rules are also checked by automated tests: 1 → `SweepCallLogUseCaseTest`; 2, 5 → `SetCallClassificationUseCaseTest`;
2, 10 → `SyncGateTest`; 3 → `ResolveCrmMatchesUseCaseTest`; 4, 6 → `PostCallPromptPolicyTest`; 7 → `SettingsViewModelTest`;
8, 9 → `SyncWorkerTest` and `UploadPendingActivitiesUseCaseTest`.

## What the automated tests check (154 tests)

| Area | Test classes | What they check |
|---|---|---|
| Work or personal | `ClassifyCallUseCaseTest` (5), `ResolveCrmMatchesUseCaseTest` (15), `SetCallClassificationUseCaseTest` (10) | Order is saved answer → work SIM → ask (the CRM never decides); an answer is saved so the number isn't asked again; only work numbers are searched in the CRM; leads are auto-created from the contact name (setting on/off, retry when offline); a choice made by hand is never changed |
| Privacy | `SyncGateTest` (6), `UploadPendingActivitiesUseCaseTest` (17), `LogHygieneTest` | Only work calls get into the upload queue; even a personal call forced into the queue is never sent; no contact name is sent; no log line can print call data |
| Finding calls | `SweepCallLogUseCaseTest` (11), `CallTypeMappingTest`, `LibPhoneNumberNormalizerTest` (5) | The 7-day limit (including a bug found on a phone); calls written late; a wiped call history; reading in pages; reading twice is harmless; Indian number formats |
| Uploading | `UploadPendingActivitiesUseCaseTest` (17), `SyncWorkerTest` (5), `ActivityUploaderImplTest` (3, fake server) | Some items failing; retry on server or network errors; a repeated upload; an edit during upload; conflicts (including a wrong clock); recovery after a crash; a deleted CRM lead; 250 waiting items sent as 100 + 100 + 50 |
| CRM | `CrmRepositoryImplTest` (6, fake server), `CreateLeadFromCallUseCaseTest` (7), `CrmLookupWorkerTest` (9) | Every server answer (200, 400, 409, 422, 5xx, dropped connection); a lead is never created twice; notifications only for undecided calls, also offline, never more than 3 |
| Database | `DatabaseTest` (7, real database queries) | No duplicate calls; linked rows deleted together; queue claims and version checks; crash reset; the CRM search list; call list filters and counts |
| Screens | `CallListViewModelTest` (7), `SyncQueueViewModelTest` (5), `CallDetailViewModelTest` (11), `RulesViewModelTest` (4), `SettingsViewModelTest` (5), `PostCallPromptPolicyTest` (5), `OemBatteryGuideTest` (4) | Filters survive Android closing the app; the form never overwrites what the user is typing; saving shows the cleaned values; Create lead (never on a personal call); no notification for missed calls; rule changes update calls; the work SIM setting is applied again |

**Test helpers:** `testing/FakeRepositories` is an in-memory stand-in for every repository, sharing one state like the
real database; `testing/TestUseCases` builds every use case on top of it; `testing/MainDispatcherRule` lets ViewModel
tests control timing.

**On-device automated tests:** `EncryptedDatabaseTest` (the database file can't be read without the key, and opens
again with the stored key) and `MigrationTest` (upgrades from version 1 to 2 and from 2 to 3 keep the data; 2 → 3 turns old automatic decisions
into saved answers).

## Testing on a real phone

Everything below was done by hand on a **Samsung Galaxy A50 (Android 11, two SIMs)** with the mock CRM. Two helpers
were used:
- a **debug-only tool** (`app/src/debug/.../CallLogSeedReceiver`, started only from adb) that adds incoming, missed,
  rejected, blocked and hidden calls, and large numbers of calls, to the call history;
- **fault switches** on the mock server (`X-Mock-Fault`, `/__admin/faults`) to make it slow or fail.

| Area | What was checked |
|---|---|
| Finding calls | Real outgoing calls picked up in about 3 s; with the app closed; after force-stop (caught up on open); call history changed without a call; after restart; after app update |
| Robustness | 450 calls at once; app crashed in the middle of reading → exactly the rest was read; broken database key → new key, database rebuilt, calls read again |
| Work / personal and CRM | (Before the 2026-10-10 call-flow change) a CRM match turned a saved contact into a work call; unknown numbers → notification (max 3); Professional / Personal / Log from the notification; a notification shown offline was sorted out when the CRM came back |
| Screens | Filters, call details form, rotating with unsaved notes, discard dialog, create lead, number rules, work SIM (with real SIMs), screen titles |
| Uploading | Upload, update, server down (503) with waiting, item error, conflict, deleted CRM lead, wrong clock; **airplane mode** (held, then uploaded by itself); **crash in the middle of a 100-item upload** (exactly 150 stored, no duplicates); **wrong token** (401 → kept → uploaded with the right build) |
| Release APK | Read, sorted and uploaded 65 calls in 7 s; 10 unknown calls never sent; no app or network logs; call list smooth with many calls; Samsung battery prompt and screen |
| Privacy | The CRM only ever got test work calls — none of the phone's real calls; the database file has no readable text |
