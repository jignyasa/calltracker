# Decisions, trade-offs, assumptions and limitations

This page explains, in plain words, the main choices made in Call Tracker and why.
(The app shows "Professional"; inside the code and the CRM the same thing is called BUSINESS.)

## 1. Work call or personal call?

**The goal:** personal calls must never leave the phone, work calls should be easy to log, and the user should be
asked as little as possible.

**The rule: decide on the phone first, ask once per number, remember the answer. Only work calls go near the CRM.**
This follows the product owner's call flow (2026-10-10).

The app checks these in order:

| Step | What the app looks at | Result | Why |
|---|---|---|---|
| 1 | Has the user already answered for this number? | Use that answer | Every answer is saved, so each number is asked about only once. |
| 2 | Was the call made on the work SIM? (optional setting) | Professional | Many phones in India have two SIMs, one of them from the company. |
| 3 | Neither, and the people actually talked (incoming or outgoing) | Unsorted, plus one "Work call?" notification | Asking once, right after the call, is easier than filling a form later. |
| — | Neither, and the call was missed or rejected | Unsorted, no notification | There's nothing to log yet; it waits in the Unsorted tab. |

**How the user can answer** (one tap each, and the answer is saved for the number):
- the notification's **Professional** button (opens the log form) or **Personal** button;
- tapping the notification (opens the call with the **Classify call** dialog);
- the Professional / Personal choice on the call screen ("Remember for this number" is ticked; untick it to change
  only this one call);
- the **Number rules** screen, where saved answers can be changed or deleted;
- the **work SIM** setting.

**After that, for work calls only, the CRM is checked.** The app searches the number in Leads and Contacts.
- **Found:** the call is linked to that lead or contact and uploaded.
- **Not found:** if "Create leads automatically" is on (the default) and the phone's contacts have a name for the
  number, a new lead is created with that name. Otherwise a **Create lead** button is shown.
- The server refuses a second lead with the same number, so duplicates are not created.

**Safety rules**
- **When in doubt, keep it on the phone.** Only calls marked Professional can be uploaded. A mistake can only mean a
  work call wasn't uploaded yet (fixed with one tap), never a personal call leaking out.
- **Personal and unsorted numbers are never sent to the CRM**, not even to search for them. A contact name is sent
  only to create a lead for a work number, and only if the setting is on.
- **A choice made by hand always wins.** A saved answer or the work SIM setting never changes it.
- **Few notifications:** only for numbers with no saved answer, only for calls that ended in the last hour (so no flood
  after a restart or the first import), never more than 3 at once, and the lock screen shows no details.

**Trade-offs**
1. A CRM customer calling from a new number is asked "Professional?" once before the CRM is searched. After that the
   number is remembered.
2. The assignment says "search the number in the CRM when a call ends". Here that happens only when the call is a work
   call, so personal numbers never leave the phone.
3. Numbers saved in the phone's contacts are still asked once, because contacts include customers too.

**Upgrading from the older version** (which searched the CRM first): calls already decided keep their answer; a
database migration turns them into saved answers.

## 2. Finding calls: the phone's call history is the truth

- **The "phone state" signal is only a wake-up call.** It doesn't say how long the call was, its type or its SIM, it
  can arrive twice, and some phones block it to save battery. The phone's **call history (CallLog)** has everything,
  so the app always reads from there.
- **It remembers where it stopped reading.** Each time, it reads only new calls (with a small 2-minute overlap) and
  never goes back further than 7 days before install. Reading the same call twice is harmless, and a crash midway
  loses nothing. The 7-day limit stops an old restored call history from flooding the app (seen on a real phone).
- **Many triggers, one job:** the end of a call, a change in the call history, phone restart, app update, opening the
  app, and an hourly safety check all start the same single read.
- **No call recording, no accessibility tricks, no need to be the default phone app** — only normal permissions that
  Google Play allows.
- **Dual SIM:** the app works out which SIM was used in several ways, because Samsung phones store the SIM's ID in a
  form apps can't read on newer Android (found on a real phone).

## 3. Uploading to the CRM (sync)

- **Save now, upload later.** Logging a call never waits for the internet. The call is put in a local upload queue
  (the "outbox") at the same moment it's saved, and uploaded in the background.
- **No duplicates.** Each call log gets a unique ID made on the phone. If an upload is sent twice (for example the
  reply was lost), the server recognises it and doesn't create a second copy. Tested by crashing the app mid-upload.
- **No lost edits.** Each time a call is changed, its queue entry gets a new version number. An upload only counts as
  done if nothing changed while it was being sent; otherwise it is sent again.
- **One bad item doesn't block the rest.** The server answers each item separately:
  - saved / already there / updated → done;
  - conflict → the newer copy wins (by time of last edit);
  - invalid → marked failed, shown on the call with a Retry button and in the Sync queue's Failed tab;
  - server error → tried again later.
  If the whole request fails (no internet, server down), everything stays in the queue.
- **Two problems found on a real phone and fixed:**
  - If a CRM lead was deleted or merged, the call used to be stuck forever. Now the old link is dropped, the number
    is searched again, and the call is re-sent with the new link.
  - If the CRM's clock was ahead, later edits on the phone could be ignored. Now every save is always stamped later
    than the version it replaces.
- **One place to see upload status:** the Sync queue screen (Pending / Failed / Synced, offline banner, Upload now).
- **Battery friendly:** uploads only run with internet, wait longer after each failure (30 s, then doubling), group
  quick edits together, send up to 100 items at a time, and an hourly check catches anything missed.

## 4. Storage and security

- **The local database is the one source of truth.** Screens only show what's in the database, so the app looks the
  same online and offline.
- **The database is encrypted** (SQLCipher). Its key is random and is locked by the phone's secure key store (Android
  Keystore), so it can't be copied out. Call history is private data.
- **No backups of the database.** If the key is ever lost, the database is rebuilt from the phone's call history; only
  notes that weren't uploaded yet are lost.


## 5. Technology choices

| Choice | Why | Other option |
|---|---|---|
| Kotlin, one module | Simple to build and find things at this size | Many modules: useful for a big team |
| XML screens + Material 3 | Runs well on cheap phones; smaller app | Jetpack Compose |
| MVVM + Clean Architecture | The important rules (privacy, sync) are plain Kotlin, so they're easy to test | — |
| Hilt | Standard Android dependency injection, works with background jobs and ViewModels | Koin / by hand |
| Room + SQLCipher | Safe, typed database with upgrades and encryption | SQLDelight |
| WorkManager | Background jobs that survive restarts, wait for internet and retry | A foreground service (uses more battery) |
| Retrofit + OkHttp + kotlinx.serialization | The common, well-known way to call an API | Ktor |
| Paging 3 | Shows thousands of calls without loading them all at once | Manual limits |
| libphonenumber | Correctly turns every Indian number format into one standard form, so matching works | Hand-written rules |
| Node/Express mock CRM | A real server to test against, including failures and duplicates | Fake code only |
| No navigation library | About ten screens in one activity; plain fragment switching is simpler | Navigation component |
| Shared colour tokens | Dark mode works on every screen for free; screens look consistent | Colours per screen |

## 6. Assumptions

- The real CRM works like the mock server (see `mock-server/README.md`): search by phone, create lead safely,
  upload calls in batches with a result per call.
- Numbers are Indian by default; numbers from other countries work if saved with the country code.
- One CRM account per phone, with a fixed token (a real app would need login).
- **The product owner's call flow is the final word** (2026-10-10): decide first, ask once per number, CRM only for
  work calls. Where it differs from the assignment, the flow wins, to protect privacy.
- One number usually means one relationship (work or personal). A single call can still be set differently.
- A name from the phone's contacts is good enough for a new lead; it can be renamed in the CRM.
- Calls older than 7 days before install are not needed.
- A follow-up has a date but no time.
- The user gives call history and phone permissions; without them the app can't work and says so.

## 7. Known limitations

- **Battery settings screens** were tested on Samsung only. Other brands use known settings pages with a fallback.
  Some phone brands may still stop background work.
- **A call already uploaded can't be deleted from the CRM** by the app. Marking it personal stops further updates, and
  the app warns about this.
- **Unsaved notes are lost** if Android closes the app in the background before Save (they do survive rotating the
  screen).
- **The database key is made on the main thread** the first time — a few hundredths of a second on slow phones.
- **Calls imported before the dual-SIM fix** have no SIM shown; changing the work SIM only re-checks the last 7 days.
- **A new number from a CRM customer is asked about once**, because the CRM is searched only for work calls.
- **A number that is both work and personal** (a friend who is also a customer) follows its saved answer; a single
  call must be changed by hand.
- **Auto-created leads have only a name and number**; someone has to add the company.
- **When the CRM's newer copy wins a conflict, the user isn't told** their edit was replaced.
- **The APKs here are signed with the debug key** (no release key is stored in the repo).
- **targetSdk is 36** (compileSdk 37); raising it needs its own review.
- **Short codes and service numbers** (198, 121…) are always Personal: they have no full +91 form, so no answer can be
  saved for them and they can't go to the CRM. If one is used for work, each call must be marked by hand.
- **Hidden / private callers are asked every time**, because there's no number to remember the answer for.
- **The mock CRM runs on the tester's computer.** A shared APK only works next to it (emulator, or a phone connected by
  USB with `adb reverse`).

## 8. Future improvements

- **Login and token refresh**, one CRM account per user, and settings sent from the server.
- **Connect to the company's real CRM** instead of the mock server.
- **Put the CRM online with HTTPS**, so the app works on any phone without a laptop or cable.
- **Remember answers for short codes** using the number as dialled.
- **Keep the note draft** even if Android closes the app.
- **Search** in the call list, and a daily summary.
- **Tell the user when a CRM conflict replaced their edit**, with the old text to copy back.
- **A list of auto-created leads** to review, fill in the company, or undo.
- **Smarter asking:** suggest a different answer when a "Personal" number calls on the work SIM during work hours.
- **More phone brands** (Tecno, Infinix, Lava) and an in-app "is background work reliable?" check.
- **Usage counts without personal data** (number of uploads, failures) to watch reliability.
