package com.example.calltracker.ui.common

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.example.calltracker.ui.settings.OemBatteryGuide

/** Android's battery optimisation and phone makers' own background killers, as far as the app can see them. */
object BackgroundRestrictions {

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    val oemGuide: OemBatteryGuide.Guide? get() = OemBatteryGuide.forManufacturer(Build.MANUFACTURER)

    /**
     * Shows the system "Stop optimising battery usage?" dialog for this app; falls back to the list of apps.
     * Call capture is the app's core function and has to run in the background after every call, which is the
     * case Google's policy allows this prompt for.
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) {
        val prompt = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", context.packageName, null))
        if (!tryStart(context, prompt)) tryStart(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    /** Opens the first of the maker's screens that exists here; else this app's system info page. */
    fun openOemSettings(context: Context, guide: OemBatteryGuide.Guide) {
        val opened = guide.targets.any { target ->
            tryStart(context, Intent().setComponent(ComponentName(target.packageName, target.className)))
        }
        if (!opened) tryStart(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    }

    /** Another app's screen may be missing or not exported on this OS version: any failure just means "try the next". */
    private fun tryStart(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
