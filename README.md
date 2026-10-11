# CallTracker — real-time call activity for a CRM

An Android app for field sales teams. It notices every phone call, asks once per number whether it is **work** or
personal, links work calls to the right CRM Lead or Contact (or creates the lead), lets the rep add notes in a few taps,
and uploads them to the CRM reliably, even on bad networks, across reboots and on aggressive OEM battery managers.
**Personal calls never leave the phone**, not even as a CRM search.

| Read this for… | |
|---|---|
| How it's built | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| Why it's built that way, assumptions, limitations, what's next | [docs/DECISIONS.md](docs/DECISIONS.md) |
| How it's tested (and what was verified on a real phone) | [docs/TESTING.md](docs/TESTING.md) |
| Phase-by-phase build log | [docs/IMPLEMENTATION_NOTES.md](docs/IMPLEMENTATION_NOTES.md) · [docs/PLAN.md](docs/PLAN.md) |
| The CRM API contract (mock server) | [mock-server/README.md](mock-server/README.md) |

---

## What it does

| | |
|---|---|
| **Call detection** | Incoming, outgoing, missed, rejected (plus blocked/voicemail/other). Number, type, start, end, duration, SIM slot. Picked up seconds after the call ends, and caught up after the app was killed, force-stopped or the phone rebooted. |
| **Professional vs personal** | Classified first: the number's saved answer, else the work SIM, else *"Work call?"* in a notification right after the call (Professional / Personal / Log). Every answer is saved, so a number is asked **once**. |
| **CRM matching** | Professional calls only: the number is looked up in Leads and Contacts (answers cached for 24 h between calls); a match links the call, including calls already uploaded. No match → the lead is created automatically from the phonebook name (Settings, on by default), or **Create lead** is offered. Personal numbers never reach the CRM. |
| **Post-call log** | Notes, outcome, follow-up date, next action, remarks — for professional calls. |
| **Offline & sync** | Everything is stored first (encrypted) on the phone; professional calls go to an outbox that uploads in batches with idempotency keys, per-item results, retries with backoff, conflict handling, and crash recovery. The **Sync queue** screen shows what is Pending, Failed or Synced, with an offline banner and Upload now. |
| **Screens** | Call activities (tabs: All / Professional / Personal / Unsorted, avatars, type and status pills), call details with the log form, Classify call dialog, after-call pop-up, Create new lead, Call activity saved, Sync queue, Number rules and Settings, all reached from a side menu. Light and dark mode. The UI says **Professional** where the code and the CRM say BUSINESS. |
| **Real-world hardening** | Dual SIM, low-end phones (paged lists, bounded work), OEM battery guidance (Xiaomi, Oppo, Realme, Vivo, Samsung, Huawei), encrypted database, no call data in logs. |

## Repository layout

```
app/            Android app (Kotlin, XML views, MVVM + Clean Architecture, single module)
  src/main      ui/ · domain/ · data/ · sync/ · telephony/ · di/
  src/test      154 JVM tests (Robolectric where Android is needed)
  src/androidTest  on-device tests (SQLCipher encryption, DB migrations v1→v2 and v2→v3)
  src/debug     debug-only test tooling (CallLog seeder; never in release)
mock-server/    Node + Express mock of the CRM API (lookup, leads, batch activities, fault injection) + tests
docs/           Architecture, decisions, testing, phase notes, flow and design review (+ images/), the assignment
dist/           Prebuilt release APKs
```

## Quick start

### 1. Prerequisites
- **JDK 17** (the Gradle daemon is pinned to 17), **Android SDK** with platform `android-37.0` and build-tools `36.0.0`
  (Android Studio installs these on sync).
- **Node.js 20+** for the mock CRM.
- A device or emulator on **Android 8.0+ (API 26)**. A real phone is best: emulators have no real dialer/SIM.

### 2. Start the mock CRM

```bash
cd mock-server
npm install
npm start            # http://localhost:3000 — seeded with a few Indian contacts/leads
npm test             # 16 API tests
```

### 3. Build and install the app

| Target | Command | CRM URL inside the app |
|---|---|---|
| **Emulator** | `./gradlew installDebug` | `http://10.0.2.2:3000/` (default) |
| **Physical phone (USB)** | `adb reverse tcp:3000 tcp:3000` then `./gradlew installDebug -PcrmBaseUrl=http://localhost:3000/` | `http://localhost:3000/` |

On Windows use `gradlew.bat` (or `./gradlew` in Git Bash). Release APK: `./gradlew assembleRelease` (same `-PcrmBaseUrl`
option) → `app/build/outputs/apk/release/app-release.apk`.

### 4. First run
1. **Open the app once** — Android doesn't deliver broadcasts to an app that was never launched.
2. Tap **Allow** for call log and phone (required), contacts and notifications (recommended).
3. The last 7 days of calls appear. Make a call: it shows up a few seconds after you hang up.
4. Optional: side menu (≡) → **Settings** → pick your **work SIM**, check **Create leads automatically** (on by
   default), and **Allow in background** (plus the maker-specific step on Xiaomi/Oppo/Vivo/Samsung/…) so calls are never
   picked up late.

Try the CRM features with the seeded data: call a number from `mock-server/src/seed.js`, answer the "Work call?"
notification with **Professional** (the call is linked to the CRM record; a number saved in your phonebook but not in
the CRM gets a lead created automatically), add notes, and watch it arrive at
`GET http://localhost:3000/api/v1/activities` (needs `Authorization: Bearer dev-token`). Answer **Personal** and that
number is never asked about again and never sent.

## How to install the app (prebuilt APK, no build needed)

Two ready-made APKs are in `dist/`:

| File | Use with | CRM address inside the app |
|---|---|---|
| `CallTracker-1.0-device.apk` | A real phone connected by USB | `http://localhost:3000/` |
| `CallTracker-1.0-emulator.apk` | An Android emulator | `http://10.0.2.2:3000/` |

The app talks to the mock CRM running on your computer, so the computer and the phone must stay connected.

**Step 1. Start the mock CRM** (in its own terminal, and leave it running):
```bash
cd mock-server
npm install          # first time only
npm start            # "Mock CRM listening on http://0.0.0.0:3000"
```
Check it in a browser: `http://localhost:3000/health` shows `{"ok":true}`.

**Step 2. Connect the phone** (skip for the emulator):
1. On the phone, turn on **Developer options → USB debugging**.
2. Connect it by USB and tap **Allow** on the "Allow USB debugging?" prompt.
3. Check it's seen: `adb devices` lists the phone as `device`.

If `adb` is "not found", use the copy in the Android SDK. Windows (PowerShell):
`& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices` (or add that `platform-tools` folder to your PATH).

**Step 3. Let the phone reach the CRM** (skip for the emulator; it reaches the computer at `10.0.2.2` by itself):
```bash
adb reverse tcp:3000 tcp:3000
adb reverse --list   # shows: tcp:3000 tcp:3000
```
Run this again whenever you unplug the phone or restart adb.

**Step 4. Install the APK:**
```bash
adb install -r dist/CallTracker-1.0-device.apk      # phone
adb install -r dist/CallTracker-1.0-emulator.apk    # emulator
```

**Step 5. Open the app once and grant permissions** — see [First run](#4-first-run). Android sends no call events to an
app that has never been opened.

**Start again with empty CRM data (optional).** The mock CRM saves its data to `mock-server/data/db.json`, so a restart
keeps it.
- See what's stored: open `http://localhost:3000/__admin/state`.
- Clear it while the server runs: `curl -X POST http://localhost:3000/__admin/reset` (PowerShell:
  `Invoke-RestMethod -Method Post http://localhost:3000/__admin/reset`). Only the sample leads and contacts remain.
- Clear the app too: `adb shell pm clear com.example.calltracker`, then open it again and grant the permissions.

Both APKs are **release builds** (R8-shrunk, no debug tooling) signed with the Android debug key. To sign with a real
key, set `releaseStoreFile`, `releaseStorePassword`, `releaseKeyAlias` and `releaseKeyPassword` as Gradle properties
(e.g. in `~/.gradle/gradle.properties`) and run `assembleRelease`.

## Build, test, lint

```bash
./gradlew assembleDebug                 # debug APK
./gradlew testDebugUnitTest             # 154 JVM tests
./gradlew lintDebug                     # 0 errors / 0 warnings, warnings fail the build
./gradlew connectedDebugAndroidTest     # on-device: SQLCipher encryption + DB migrations
./gradlew assembleRelease               # R8 release APK
cd mock-server && npm test              # 16 mock API tests
```

Details, the device test plan and results: [docs/TESTING.md](docs/TESTING.md).

## Deliverables 

| Asked for | Where |
|---|---|
| Complete source code | `app/`, `mock-server/` |
| Build instructions | [Quick start](#quick-start) · [Build, test, lint](#build-test-lint) |
| APK | [`dist/`](#how-to-install-the-app-prebuilt-apk-no-build-needed) |
| README explaining setup | this file |
| Architecture overview | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| Design decisions and trade-offs (incl. the professional-vs-personal reasoning) | [docs/DECISIONS.md](docs/DECISIONS.md) §1–5 |
| Assumptions | [docs/DECISIONS.md](docs/DECISIONS.md) §6 |
| Testing approach | [docs/TESTING.md](docs/TESTING.md) |
| Known limitations | [docs/DECISIONS.md](docs/DECISIONS.md) §7 |
| Future improvements | [docs/DECISIONS.md](docs/DECISIONS.md) §8 |

## Tech stack

Kotlin · XML layouts + ViewBinding + Material 3 · MVVM + Clean Architecture · Hilt · Coroutines/Flow · Room +
SQLCipher (Keystore-wrapped key) · Paging 3 · WorkManager · Retrofit + OkHttp + kotlinx.serialization · libphonenumber ·
JUnit4, MockK/fakes, Turbine, Robolectric, MockWebServer. Why each: [docs/DECISIONS.md](docs/DECISIONS.md).
