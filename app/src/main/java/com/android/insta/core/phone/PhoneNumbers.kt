package com.android.insta.core.phone

import android.content.Context
import android.telephony.TelephonyManager
import io.michaelrocks.libphonenumber.android.PhoneNumberUtil
import java.util.Locale

/** A country for the phone picker: ISO region ("IN"), localised name, dial code (91) and flag emoji. */
data class Country(val region: String, val name: String, val dialCode: Int) {
    val flag: String
        get() = region.uppercase().map { Character.toChars(0x1F1E6 + (it - 'A')).concatToString() }.joinToString("")
}

/** Phone number rules shared by sign-in, onboarding and change phone. The server validates again. */
interface PhoneNumbers {
    /** The device's country (SIM/network, then locale), used to preselect the picker. */
    val defaultRegion: String

    fun countries(): List<Country>

    fun country(region: String): Country

    /** National (or +international) [input] for [region] → E.164 (`+919876543210`), or null if it isn't a valid number. */
    fun toE164(region: String, input: String): String?

    /** E.164 → readable international form (`+91 98765 43210`). */
    fun format(e164: String): String
}

class LibPhoneNumbers(context: Context) : PhoneNumbers {
    private val util = PhoneNumberUtil.createInstance(context.applicationContext)
    private val locale = Locale.getDefault()

    override val defaultRegion: String = run {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        listOfNotNull(telephony?.simCountryIso, telephony?.networkCountryIso, locale.country)
            .map { it.uppercase() }
            .firstOrNull { it in util.supportedRegions }
            ?: "US"
    }

    private val allCountries: List<Country> by lazy {
        util.supportedRegions.map(::country).sortedBy { it.name }
    }

    override fun countries(): List<Country> = allCountries

    override fun country(region: String): Country =
        Country(region, Locale.Builder().setRegion(region).build().getDisplayCountry(locale), util.getCountryCodeForRegion(region))

    override fun toE164(region: String, input: String): String? {
        val digits = input.trim()
        if (digits.isEmpty()) return null
        val number = runCatching { util.parse(digits, region) }.getOrNull() ?: return null
        return if (util.isValidNumber(number)) util.format(number, PhoneNumberUtil.PhoneNumberFormat.E164) else null
    }

    override fun format(e164: String): String =
        runCatching { util.format(util.parse(e164, null), PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL) }.getOrDefault(e164)
}
