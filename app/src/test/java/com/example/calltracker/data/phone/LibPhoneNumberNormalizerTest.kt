package com.example.calltracker.data.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibPhoneNumberNormalizerTest {

    private val normalizer = LibPhoneNumberNormalizer()

    @Test
    fun `indian mobile formats normalize to the same E164`() {
        listOf(
            "9876543210",
            "09876543210",
            "+91 98765 43210",
            "+91-98765-43210",
            "(+91) 98765 43210",
            "0091 98765 43210",
        ).forEach { assertEquals(it, "+919876543210", normalizer.normalize(it)) }
    }

    @Test
    fun `landline with STD code`() {
        assertEquals("+918047112233", normalizer.normalize("080 4711 2233"))
    }

    @Test
    fun `foreign numbers keep their country code`() {
        assertEquals("+14155552671", normalizer.normalize("+1 415 555 2671"))
    }

    @Test
    fun `private, unknown and payphone markers are null`() {
        listOf(null, "", "  ", "-1", "-2", "-3", "Unknown", "Private").forEach { assertNull(it, normalizer.normalize(it)) }
    }

    @Test
    fun `too short to be a number is null`() {
        assertNull(normalizer.normalize("12"))
    }
}
