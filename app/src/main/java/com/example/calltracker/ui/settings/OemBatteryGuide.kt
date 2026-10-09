package com.example.calltracker.ui.settings

import androidx.annotation.StringRes
import com.example.calltracker.R

/**
 * Phone makers whose battery managers kill background work beyond what Android's own battery optimisation does, and
 * where their setting lives. Screens move between OS versions, so each maker has several known components, tried in
 * order; if none opens, the app's system info page is the fallback. Packages are also listed in the manifest's
 * `<queries>` so they can be resolved on Android 11+.
 */
object OemBatteryGuide {

    data class Target(val packageName: String, val className: String)

    data class Guide(
        @StringRes val brand: Int,
        @StringRes val steps: Int,
        val targets: List<Target>,
    )

    fun forManufacturer(manufacturer: String): Guide? {
        val maker = manufacturer.trim().lowercase()
        return guides.entries.firstOrNull { (names, _) -> names.any { maker.contains(it) } }?.value
    }

    private val guides: Map<List<String>, Guide> = mapOf(
        listOf("xiaomi", "redmi", "poco") to Guide(
            R.string.oem_xiaomi,
            R.string.oem_steps_xiaomi,
            listOf(
                Target("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                Target("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"),
            ),
        ),
        listOf("oppo", "realme", "oneplus") to Guide(
            R.string.oem_oppo_realme,
            R.string.oem_steps_oppo_realme,
            listOf(
                Target("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                Target("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                Target("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
                Target("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
            ),
        ),
        listOf("vivo", "iqoo") to Guide(
            R.string.oem_vivo,
            R.string.oem_steps_vivo,
            listOf(
                Target("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                Target("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
                Target("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            ),
        ),
        listOf("samsung") to Guide(
            R.string.oem_samsung,
            R.string.oem_steps_samsung,
            listOf(
                Target("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                Target("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                Target("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            ),
        ),
        listOf("huawei", "honor") to Guide(
            R.string.oem_huawei,
            R.string.oem_steps_huawei,
            listOf(
                Target("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                Target("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            ),
        ),
    )

    /** For the manifest's `<queries>`; kept in sync by OemBatteryGuideTest. */
    val allPackages: Set<String> get() = guides.values.flatMap { guide -> guide.targets.map { it.packageName } }.toSet()
}
