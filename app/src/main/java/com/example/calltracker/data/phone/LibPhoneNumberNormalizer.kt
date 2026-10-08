package com.example.calltracker.data.phone

import com.example.calltracker.domain.phone.PhoneNumberNormalizer
import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat

/**
 * libphonenumber-backed normalizer. Numbers without a country code are read as [defaultRegion] (India).
 *
 * Uses `isPossibleNumber` rather than `isValidNumber`: we must never drop a real caller because the
 * metadata bundled with the app is older than a newly allocated number range.
 */
class LibPhoneNumberNormalizer(
    private val defaultRegion: String = DEFAULT_REGION,
    private val util: PhoneNumberUtil = PhoneNumberUtil.getInstance(),
) : PhoneNumberNormalizer {

    override fun normalize(raw: String?): String? {
        val input = raw?.trim().orEmpty()
        // CallLog reports private / unknown / payphone callers as "-1", "-2", "-3" (or empty).
        if (input.isEmpty() || input.startsWith("-")) return null
        if (input.none { it.isDigit() }) return null

        return try {
            val parsed = util.parse(input, defaultRegion)
            if (util.isPossibleNumber(parsed)) util.format(parsed, PhoneNumberFormat.E164) else null
        } catch (_: NumberParseException) {
            null
        }
    }

    companion object {
        const val DEFAULT_REGION = "IN"
    }
}
