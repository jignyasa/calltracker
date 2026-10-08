# CallTracker: Phase Plan

Spec: `docs/assignment.pdf`. Project rules: `CLAUDE.md`.

## Business vs personal classification
A layered rule engine that keeps user taps to a minimum:
1. An explicit per-number user rule ("always business" / "always personal") wins.
2. Number matches a CRM Lead/Contact → BUSINESS automatically.
3. Optional "work SIM" setting (dual SIM): calls on that SIM → BUSINESS by default.
4. Number is in the device's personal contacts and not in the CRM → PERSONAL.
5. Otherwise UNCLASSIFIED → post-call notification with "Business / Personal / Log" actions. It stays local until the user decides.

Default-deny: anything not positively BUSINESS is never synced.

## Progress
- [x] Phase 0: Foundation (2026-10-08)
- [x] Phase 1: Mock CRM server (2026-10-08). 16 `node:test` tests; `scripts/demo.sh` walkthrough
- [ ] Phase 2: Domain + data core

## Phases

| Phase | Scope | Exit criteria |
|---|---|---|
| **0. Foundation** | Remove the Compose template, switch to XML + ViewBinding + Material; add Hilt, KSP, Room + SQLCipher, Retrofit/OkHttp/kotlinx.serialization, WorkManager, libphonenumber to the version catalog (check AGP 9 compatibility for Hilt/KSP); package skeleton `ui/domain/data/sync/telephony/di`; `git init` | `assembleDebug` green; app launches an empty XML screen |
| **1. Mock CRM server** | `/mock-server` Express: `GET /contacts/lookup?phone=`, `POST /leads`, `POST /activities/batch` (idempotency store, per-item results), fault-injection flags, seed data with Indian numbers | `curl` scripts show idempotent replay and mixed per-item results |
| **2. Domain + data core** | Domain models (`CallRecord`, `CallType`, `Classification`, `Activity`, `Lead`), use cases, Room schema (calls, activities, outbox, number_rules, crm_cache, watermark), Keystore-wrapped SQLCipher passphrase, `PhoneNumberNormalizer` (IN) | DAO, normalizer, and use-case unit tests pass |
| **3. Call detection** | Runtime permissions flow (READ_CALL_LOG, READ_PHONE_STATE, READ_CONTACTS, POST_NOTIFICATIONS), PHONE_STATE receiver → sweep, `CallLogReader` (dual-SIM `PHONE_ACCOUNT_ID`, type mapping, REJECTED), watermark sweep worker, BOOT_COMPLETED + app-open + periodic triggers | Calls made on a device/emulator show up in Room, including calls made while the app was force-stopped (caught up on the next trigger) |
| **4. Matching + classification** | CRM lookup (cache first, network fallback), the rule engine above, post-call notification with actions, "create Lead from call" | Tests cover rule precedence; a number with no match → UNCLASSIFIED |
| **5. UI** | Call list (filter by classification/sync status), call detail + activity log form (notes, outcome, follow-up date picker, next action, remarks), create-lead screen, number rules screen, settings (work SIM, battery-optimization help) | ViewModel tests with Turbine; manual run-through on the emulator |
| **6. Sync engine** | Outbox enqueue (BUSINESS-only gate), `SyncWorker` batch upload with Idempotency-Key, per-item status, backoff, unique work, connectivity constraint, stale IN_FLIGHT recovery, 409 handling, sync status in UI | MockWebServer + worker tests: retry, partial failure, duplicate replay, personal call never sent |
| **7. Hardening** | OEM battery-optimization guidance (Xiaomi/Oppo/Vivo/Realme/Samsung deep links, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` prompt), large call volumes (paging, batch sizes), log masking, R8 config, release APK | Lint clean; release APK builds; smoke test over a 50+ call backlog |
| **8. Docs & delivery** | README (setup, build, mock server), architecture diagram, decisions/trade-offs, assumptions, testing approach, known limitations, future improvements, APK | Every deliverable in the spec is present |
