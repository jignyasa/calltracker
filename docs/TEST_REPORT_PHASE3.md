# Test report: Phases 0–3 (device run)

Date: 2026-10-09 · Device: Samsung Galaxy A50 (SM-A505F), Android 11 (API 30), dual SIM (Airtel + Jio)
> **Status (2026-10-09, after Phase 7):** of the open findings in section 5, #3 (list scrolls to new calls), #4 ("1h 02m" durations), #5 (short codes show their number) and #6 (lint, now 0/0) are fixed; #1 (a short sweep burst per call) is kept by design as cheap and bounded; #2 (database opened on the main thread on first use) is documented in DECISIONS.md §7. The bug in section 4 is fixed, with a regression test and a DB migration.

Scope: everything built through Phase 3. That is the XML/Hilt foundation, the mock CRM server, the domain and encrypted Room core, and call detection through the CallLog sweep.

CRM matching, BUSINESS classification in the running app, notifications, the detail and rules screens, and network sync start in Phase 4. So on the device, every call shows as **Personal** (it has a contact name) or **Unsorted** (UNCLASSIFIED). Rule precedence and the outbox gate are covered by unit tests only.

## Summary

| Area | Result |
|---|---|
| Build (`assembleDebug`) | ✅ |
| JVM unit tests | ✅ 43/43 (42 before the fix, plus 1 regression test) |
| Instrumented tests on the device | ✅ `EncryptedDatabaseTest`, `MigrationTest` |
| Lint (`lintDebug`) | ✅ 0 errors, 9 warnings (listed below) |
| Mock CRM server | ✅ 16/16 `node:test`, 20/20 manual curl checks |
| Device scenarios | ✅ all run scenarios pass; 1 bug found and **fixed** (see "Bug fixed") |

## 1. Automated tests

| Suite | Tests | What it covers |
|---|---|---|
| `ClassifyCallUseCaseTest` | 6 | Precedence: user rule → CRM → work SIM → device contact → UNCLASSIFIED |
| `SyncGateTest` | 6 | Only BUSINESS calls reach the outbox; BUSINESS→PERSONAL removes the unsynced item |
| `SetCallClassificationUseCaseTest` | 7 | Manual classification wins and is reconciled in the same transaction |
| `SaveActivityLogUseCaseTest` | 5 | Editing an activity re-enqueues it (generation bump) |
| `SweepCallLogUseCaseTest` | 9 | Watermark, 7-day backfill, paging, dedup, overlap, permission denied, **backfill floor** |
| `CallTypeMappingTest` | 1 | CallLog TYPE → `CallType` |
| `LibPhoneNumberNormalizerTest` | 5 | Indian formats → E.164 |
| `DatabaseTest` (Robolectric) | 4 | DAOs, unique constraints, cascades, outbox generations |
| `EncryptedDatabaseTest` (device) | 1 | SQLCipher file isn't plaintext and reopens with the stored key |
| `MigrationTest` (device) | 1 | v1 → v2 schema and the backfill floor value |

## 2. Mock CRM server (`/mock-server`)

`npm test`: 16/16 pass. Manual curl checks against `npm start`:

| # | Scenario | Result |
|---|---|---|
| M1 | Lookup `9876543210`, `+91 98765 43210` and `09876543210` | ✅ 200, CONTACT, normalised to `+919876543210` |
| M2 | Lookup of a seeded lead | ✅ LEAD |
| M3 | Unknown valid number | ✅ `match: null` |
| M4 | Invalid number | ✅ 400 `INVALID_PHONE` |
| M5 | Missing or wrong token | ✅ 401 |
| M6–M7 | Create a lead, then replay the same Idempotency-Key and body | ✅ 201, same lead, `Idempotent-Replayed: true` |
| M8 | Same key, different body | ✅ 422 `IDEMPOTENCY_KEY_REUSED` |
| M9 | Phone already in the CRM | ✅ 409 `PHONE_EXISTS` with the match |
| M10 | No Idempotency-Key | ✅ 400 |
| M11–M14 | Batch: CREATED, then DUPLICATE, UPDATED (newer `updatedAt`), CONFLICT (stale) | ✅ |
| M15 | One invalid and one valid item in the same batch | ✅ INVALID and CREATED, each handled on its own |
| M16 | 101 items | ✅ 413 |
| M17 | `X-Mock-Fault` 500, 503, 429, timeout, drop | ✅ |
| M18 | `itemFailureRate: 0.5` | ✅ mixed ERROR and CREATED |
| M19 | `forceStatus: 503, forceCount: 3` | ✅ three 503s, then 200 |
| M20 | `/__admin/reset` | ✅ seed data restored |

Not run: persistence of `data/db.json` across a restart (the server ran with `DATA_FILE=memory`).

## 3. Device scenarios

Real outgoing calls went to the carrier helpline (121). There was no second phone, so incoming, missed, rejected, blocked and private calls were simulated with fake CallLog rows (see "Test tooling").

### Permissions
| # | Scenario | Result |
|---|---|---|
| P1 | Fresh install, first open | ✅ permission card shown, "No calls yet" |
| P2 | Grant permissions | ✅ card hides; the first sweep imports the last 7 days |
| P3/P4 | Deny once; deny with "don't ask again" → button becomes "Open settings" | ⏳ **Not verified.** The dialogs were answered with Allow on the phone during the run |
| P7 | Permission revoked while the app has data | ✅ card returns, data kept; sweeps log "permission missing", no crash |
| P8 | Rotation and dark mode | ✅ layout correct, no crash |

### Call types and classification
| # | Scenario | Result |
|---|---|---|
| C4 | Real outgoing call | ✅ imported about 3 s after hang-up with the correct duration |
| C5 | Blocked, rejected, answered externally | ✅ "Blocked", "Rejected", "Other" |
| C6 | Private number | ✅ "Private number" |
| C8 | Duration over 1 h | ⚠️ shows `62m 05s`; `1h 02m` would read better |
| K1 | Row with a contact name (`CACHED_NAME`) | ✅ Personal |
| K2 | Unknown number | ✅ Unsorted |
| – | VOICEMAIL rows | ℹ️ Can't be written or read through `CallLog.Calls.CONTENT_URI` (needs `allow_voicemails`), so they never reach the app |

### Triggers and reliability
| # | Scenario | Result |
|---|---|---|
| T1 | App in the foreground, real call | ✅ PHONE_STATE IDLE → sweep → imported |
| T3 | Process killed, real call | ✅ the broadcast started the app; imported without opening it |
| T4 | Force-stopped, real call, then the app opened | ✅ nothing arrives while stopped (normal Android behaviour); caught up on open |
| T5 | CallLog changed with no phone-state change | ✅ content trigger → `CallLogChangedWorker` → sweep about 2 s later; trigger re-armed |
| T6/T7 | Row written just before a reboot | ✅ imported after boot. Samsung delivered `BOOT_COMPLETED` about 6 min after unlock |
| T8 | Reinstall (`MY_PACKAGE_REPLACED`) | ✅ sweep runs, data kept |
| T9 | Hourly periodic sweep | ✅ ran on schedule (forcing it early is correctly refused by WorkManager) |
| T10 | Two sweeps at the same moment (boot one-shot + periodic) | ✅ no duplicates |

### Watermark, volume and crash safety
| # | Scenario | Result |
|---|---|---|
| W1 | Rows dated 3 and 10 days ago before the first sweep | ✅ only the 3-day row imported. ❌ The 10-day row was imported on the next sweep (bug, now fixed) |
| W2 | Row with an old date (1 day) inserted later | ✅ imported by ID |
| W3 | 300 and 450 rows at once | ✅ exactly 300 and 450 imported, in pages of 200 |
| W4 | App crashed (`am crash`) 0.8 s into a 300-row sweep | ✅ the next sweep imported exactly the remainder (301); no duplicates or gaps |

### Privacy and security
| # | Scenario | Result |
|---|---|---|
| S1 | App log lines | ✅ only call state and row counts; no numbers or names |
| S2 | DB file and 1.1 MB WAL pulled from the device | ✅ no SQLite header, no readable numbers or names (SQLCipher) |
| S3 | Backup | ✅ no `ALLOW_BACKUP` flag |
| S4 | Wrapped DB key corrupted | ✅ `AEADBadTagException` caught; new key; DB recreated; 908 calls re-imported; no crash |

## 4. Bug fixed: 7-day backfill only applied to the first sweep

**Symptom:** after the first sweep, any CallLog row with a newer `_ID` was imported, whatever its date. On the device, a row dated 10 days ago was imported on the second sweep. In real use, a call history restored from a backup after install would flood the app with old calls.

**Cause:** the floor `now − 7 days` was only part of the initial watermark. The query `(_ID > lastId OR DATE > lastStartedAt − overlap)` had no date floor once `lastId` was set.

**Fix:**
- `SweepWatermark` has a new `notBefore` field. It is fixed on the first sweep at first-sweep time − 7 days and persisted in `sweep_state.not_before`.
- `AndroidCallLogSource` adds `AND DATE >= notBefore` to every query. `FakeCallLogSource` mirrors it.
- The DB goes from version 1 to 2 with `MIGRATION_1_2` in `data/local/Migrations.kt`. Existing installs get `not_before = last_sweep_at − 7 days`. Real late-committed rows are minutes old, so they still pass.

**Verification:**
- New unit test: `old rows with a newer id are never imported on later sweeps`.
- `MigrationTest` checks the schema and the floor value.
- The new APK was installed over the real encrypted v1 database on the device; it upgraded without errors.
- The repro was repeated on the device. Sweep 1 scanned only the 3-day row, and sweep 2 only the new row; the 10-day row was never read.

## 5. Open findings

| # | Severity | Finding | Where |
|---|---|---|---|
| 1 | Low (efficiency) | One real call causes about 5 back-to-back sweeps. Samsung rewrites the new row several times, each rewrite re-fires the content trigger, and two IDLE broadcasts each queue a sweep. Each sweep is cheap (about 3 rows, 0 imported). | `SweepScheduler`, `CallLogSweepWorker` |
| 2 | Low (performance) | The Keystore unwrap and DB setup run on the main thread on first access (ViewModel creation in `CallListFragment.onStart`). That's a jank risk on low-end phones. | `DatabaseKeyProvider`, `AppDatabaseFactory` |
| 3 | Low (UI) | New calls land above the visible part of the list, and it doesn't scroll to the top. | `CallListFragment` |
| 4 | Low (UI) | Durations over an hour show as minutes (`62m 05s`). | `Formatters.duration` |
| 5 | Low (UI, from code reading) | A number that can't be normalised to E.164 (a short code such as 198 with no contact name) shows as "Private number". | `CallAdapter.kt:31` |
| 6 | Info | Lint warnings: `commit()` vs `apply()` / KTX `edit` in `DatabaseKeyProvider`; AGP 9.4.1 and core-ktx 1.19.1 available; targetSdk not the latest; unnecessary `mipmap-anydpi-v26`; overdraw and missing `contentDescription` in `item_call.xml`; unused `R.color.black`. | — |

## 6. Not tested
- The deny and "don't ask again" permission flow (P3/P4)
- Real incoming, missed or rejected calls (no second phone available)
- The SIM slot of each call (not shown in the UI yet, and the DB is encrypted)
- The release build (`assembleRelease`), mock-server persistence, and `POST_NOTIFICATIONS` (it only exists on API 33+)

## 7. Test tooling

**Debug-only CallLog seeder:** `app/src/debug/java/.../debug/CallLogSeedReceiver.kt`. On this Samsung, `adb shell content insert` into the CallLog is denied. The receiver is in the debug build only. It is guarded by the `DUMP` permission, so only adb can trigger it. Every fake number starts with `+9190000`.

```bash
adb shell pm grant com.example.calltracker android.permission.WRITE_CALL_LOG
adb shell am broadcast -n com.example.calltracker/.debug.CallLogSeedReceiver --es seed types
#   types | old | contact | late | bulk --ei count 150 --ei base 6000 | count --ei base 6000 | cleanup
adb shell pm revoke com.example.calltracker android.permission.WRITE_CALL_LOG
```

Notes:
- Keep `bulk` to about 150–200 rows per broadcast. Inserts are slow on Samsung (roughly 3–8 rows/s), and a broadcast is killed after 60 s.
- Don't seed with `am instrument`. It force-stops the app, and WorkManager then reschedules its jobs, which disturbs the trigger tests.

**Other useful commands:**
- Real outgoing call: `adb shell am start -a android.intent.action.CALL -d tel:121`, then hang up with `adb shell input keyevent KEYCODE_ENDCALL`
- Kill the app in the middle of a sweep: `adb shell am crash <pid>`. `am kill` won't kill a process that is running a job.
- Watch sweeps: `adb logcat -s CallStateReceiver CallLogSweepWorker WM-WorkerWrapper`
