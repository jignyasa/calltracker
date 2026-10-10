# AndroidManifest

What `app/src/main/AndroidManifest.xml` declares and why. The manifest tells Android what the app may do, which
components it has, and which system events wake it up.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Talk to the CRM server. WorkManager uses network state for `NetworkType.CONNECTED` constraints. |
| `READ_CALL_LOG` | The CallLog is the source of truth for each call's number, type, start, duration and SIM. |
| `READ_PHONE_STATE` | Receive the `PHONE_STATE` broadcast (ringing / off-hook / idle). Used only as a trigger to sweep the CallLog. |
| `READ_CONTACTS` | Phonebook names, shown in the app and used as the lead name when a lead is auto-created. |
| `POST_NOTIFICATIONS` | The post-call "Professional or Personal?" prompt. Requested at runtime on Android 13+. |
| `RECEIVE_BOOT_COMPLETED` | Catch up on calls after the phone restarts. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Lets Settings show the system "stop optimising battery" prompt, so background capture isn't killed. |
| `SYSTEM_ALERT_WINDOW` | "Display over other apps": lets the log form pop up right after a business call. Granted by the user in Settings. |

## `<queries>`

On Android 11+ an app can't see other installed apps unless it lists them. The listed packages are the phone makers'
battery / auto-start screens (Xiaomi, Oppo, OnePlus, Vivo, Samsung, Huawei). `ui/settings/OemBatteryGuide` uses them to
open the right screen on the user's phone. `OemBatteryGuideTest` checks that this list matches the code.

## `<uses-feature android.hardware.telephony required="false">`

Without it the Play Store infers telephony is required (from the phone permissions) and hides the app from devices
without a phone radio, such as tablets.

## `<application>`

- `android:name=".CallTrackerApp"`: the `@HiltAndroidApp` class. It sets up Hilt and supplies WorkManager's
  `HiltWorkerFactory`.
- **Privacy:** `allowBackup="false"`, plus `data_extraction_rules.xml` (Android 12+) and `backup_rules.xml` (older
  versions) exclude the database, shared prefs and files from cloud backup and device transfer. Call data never leaves the
  phone that way.
- `networkSecurityConfig` (`network_security_config.xml`): plain HTTP is blocked everywhere except `10.0.2.2` (the
  emulator's alias for the host machine) and `localhost` (a physical device with `adb reverse tcp:3000 tcp:3000`), so the
  mock CRM server can run on HTTP.

## Components

| Component | Exported | Role |
|---|---|---|
| `ui.MainActivity` | yes (launcher) | The only main activity. Screens are fragments pushed by `AppNavigator`. `singleTop` reuses the open screen when a notification is tapped; `adjustResize` keeps form fields above the keyboard. |
| `telephony.CallStateReceiver` | yes, guarded by `READ_PHONE_STATE` | Listens for `PHONE_STATE`, which is on the implicit-broadcast exemption list, so a manifest receiver still works on API 26+. Reads no call data; on `IDLE` it schedules a CallLog sweep. |
| `telephony.BootReceiver` | no | `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` → catch-up sweep and re-arm background work. |
| `ui.postcall.PostCallActivity` | no | Post-call pop-up. `taskAffinity=""` gives it its own task so the main app isn't brought along; `excludeFromRecents` keeps it out of recent apps; dialog-style `Theme.CallTracker.PostCall`. |
| `ui.notifications.PostCallActionReceiver` | no | Handles notification action buttons (Personal / Professional) without opening a screen. |

### WorkManager initializer removal

```xml
<provider android:name="androidx.startup.InitializationProvider" tools:node="merge">
    <meta-data android:name="androidx.work.WorkManagerInitializer" tools:node="remove" />
</provider>
```

By default WorkManager starts itself at app launch with a plain worker factory, which can't build `@HiltWorker` classes
that need injected dependencies. Removing the default initializer makes WorkManager start on first use, using the
configuration from `CallTrackerApp` (`Configuration.Provider` + `HiltWorkerFactory`). `merge` keeps androidx.startup's
other initializers; `remove` drops only WorkManager's.

## What isn't in the manifest

- The CallLog content-URI trigger (`CallLogChangedWorker`), the hourly sweep and sync are WorkManager jobs scheduled
  in code, so they need no manifest entry.
- There is no foreground Service: by design, all background work runs in `CoroutineWorker`.
