package com.example.calltracker.ui.settings

import com.example.calltracker.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OemBatteryGuideTest {

    @Test
    fun `known makers get their guide, whatever the casing or sub-brand`() {
        assertEquals(R.string.oem_xiaomi, OemBatteryGuide.forManufacturer("Xiaomi")?.brand)
        assertEquals(R.string.oem_xiaomi, OemBatteryGuide.forManufacturer("POCO")?.brand)
        assertEquals(R.string.oem_oppo_realme, OemBatteryGuide.forManufacturer("realme")?.brand)
        assertEquals(R.string.oem_oppo_realme, OemBatteryGuide.forManufacturer("OPPO")?.brand)
        assertEquals(R.string.oem_vivo, OemBatteryGuide.forManufacturer("vivo")?.brand)
        assertEquals(R.string.oem_samsung, OemBatteryGuide.forManufacturer("samsung")?.brand)
        assertEquals(R.string.oem_huawei, OemBatteryGuide.forManufacturer("HONOR")?.brand)
    }

    @Test
    fun `stock Android needs no maker-specific step`() {
        assertNull(OemBatteryGuide.forManufacturer("Google"))
        assertNull(OemBatteryGuide.forManufacturer("motorola"))
    }

    @Test
    fun `every maker has at least one screen to try`() {
        listOf("xiaomi", "oppo", "vivo", "samsung", "huawei").forEach {
            assertTrue(it, OemBatteryGuide.forManufacturer(it)!!.targets.isNotEmpty())
        }
    }

    @Test
    fun `every package the guide opens is declared in the manifest's queries`() {
        // Unit tests run with the module directory as the working directory.
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val declared = Regex("""<package android:name="([^"]+)"""").findAll(manifest).map { it.groupValues[1] }.toSet()
        assertEquals(OemBatteryGuide.allPackages, declared)
    }
}
