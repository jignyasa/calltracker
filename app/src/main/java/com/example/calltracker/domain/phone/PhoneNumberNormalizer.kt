package com.example.calltracker.domain.phone

interface PhoneNumberNormalizer {
    /** E.164 (e.g. `+919876543210`), or null for private/unknown/invalid numbers. */
    fun normalize(raw: String?): String?
}
