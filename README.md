# CallTracker — real-time call activity for a CRM

An Android app for field sales teams. It notices every phone call, works out whether it was a **work** call, links it to
the right CRM Lead or Contact, lets the rep add notes in a few taps, and uploads it to the CRM reliably, even on bad
networks, across reboots and on aggressive OEM battery managers. **Personal calls never leave the phone.**

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
| **CRM matching** | Each number is looked up in the CRM (cached for 24 h); a match links the call to the Lead/Contact. No match → **Create lead** from the call. |
| **Business vs personal** | Decided automatically whenever there is a signal: your rule for the number → CRM match → work SIM → saved phone contact. Only calls with no signal ask *"Work call?"* in a notification right after the call (Business / Personal / Log). |
| **Post-call log** | Notes, outcome, follow-up date, next action, remarks — for business calls. |
| **Offline & sync** | Everything is stored first (encrypted) on the phone; business calls go to an outbox that uploads in batches with idempotency keys, per-item results, retries with backoff, conflict handling, and crash recovery. |
| **Real-world hardening** | Dual SIM, low-end phones (paged lists, bounded work), OEM battery guidance (Xiaomi, Oppo, Realme, Vivo, Samsung, Huawei), encrypted database, no call data in logs. |

## Repository layout

```
app/            Android app (Kotlin, XML views, MVVM + Clean Architecture, single module)
  src/main      ui/ · domain/ · data/ · sync/ · telephony/ · di/
  src/test      130 JVM tests (Robolectric where Android is needed)
  src/androidTest  on-device tests (SQLCipher encryption, DB migration)
  src/debug     debug-only test tooling (CallLog seeder; never in release)
mock-server/    Node + Express mock of the CRM API (lookup, leads, batch activities, fault injection) + tests
docs/           Architecture, decisions, testing, phase notes, the assignment
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
4. Optional: ⋮ → **Settings** → pick your **work SIM**, and **Allow in background** (plus the maker-specific step on
   Xiaomi/Oppo/Vivo/Samsung/…) so calls are never picked up late.

Try the CRM features with the seeded data: call (or create a lead for) a number from `mock-server/src/seed.js`, add
notes on a business call, and watch it arrive at `GET http://localhost:3000/api/v1/activities` (needs
`Authorization: Bearer dev-token`).

## Prebuilt APKs (`dist/`)

| File | Use with |
|---|---|
| `CallTracker-1.0-emulator.apk` | An emulator; mock CRM on the host at `10.0.2.2:3000` |
| `CallTracker-1.0-device.apk` | A phone over USB with `adb reverse tcp:3000 tcp:3000` |

Both are **release builds** (R8-shrunk, no debug tooling) signed with the Android debug key — install with
`adb install dist/<file>.apk`. To sign with a real key, set `releaseStoreFile`, `releaseStorePassword`, `releaseKeyAlias`
and `releaseKeyPassword` as Gradle properties (e.g. in `~/.gradle/gradle.properties`) and run `assembleRelease`.

## Build, test, lint

```bash
./gradlew assembleDebug                 # debug APK
./gradlew testDebugUnitTest             # 130 JVM tests
./gradlew lintDebug                     # 0 errors / 0 warnings, warnings fail the build
./gradlew connectedDebugAndroidTest     # on-device: SQLCipher encryption + DB migration
./gradlew assembleRelease               # R8 release APK
cd mock-server && npm test              # 16 mock API tests
```

Details, the device test plan and results: [docs/TESTING.md](docs/TESTING.md).

## Deliverables (from the assignment)

| Asked for | Where |
|---|---|
| Complete source code | `app/`, `mock-server/` |
| Build instructions | [Quick start](#quick-start) · [Build, test, lint](#build-test-lint) |
| APK | [`dist/`](#prebuilt-apks-dist) |
| README explaining setup | this file |
| Architecture overview | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| Design decisions and trade-offs (incl. the business-vs-personal reasoning) | [docs/DECISIONS.md](docs/DECISIONS.md) §1–5 |
| Assumptions | [docs/DECISIONS.md](docs/DECISIONS.md) §6 |
| Testing approach | [docs/TESTING.md](docs/TESTING.md) |
| Known limitations | [docs/DECISIONS.md](docs/DECISIONS.md) §7 |
| Future improvements | [docs/DECISIONS.md](docs/DECISIONS.md) §8 |

## Tech stack

Kotlin · XML layouts + ViewBinding + Material 3 · MVVM + Clean Architecture · Hilt · Coroutines/Flow · Room +
SQLCipher (Keystore-wrapped key) · Paging 3 · WorkManager · Retrofit + OkHttp + kotlinx.serialization · libphonenumber ·
JUnit4, MockK/fakes, Turbine, Robolectric, MockWebServer. Why each: [docs/DECISIONS.md](docs/DECISIONS.md).
