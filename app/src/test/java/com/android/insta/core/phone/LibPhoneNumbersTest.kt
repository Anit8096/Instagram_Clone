package com.android.insta.core.phone

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.insta.R
import com.android.insta.core.network.AppError
import com.android.insta.core.ui.UiMessage
import com.android.insta.core.ui.retryAfterSeconds
import com.android.insta.core.ui.sanitizeCode
import com.android.insta.core.ui.toAuthMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The real libphonenumber metadata (loaded from the APK's assets, here through Robolectric). */
@RunWith(AndroidJUnit4::class)
class LibPhoneNumbersTest {
    private val phones = LibPhoneNumbers(ApplicationProvider.getApplicationContext())

    @Test
    fun `national input becomes E164 for the chosen country`() {
        assertEquals("+919876543210", phones.toE164("IN", "98765 43210"))
        assertEquals("+12015550101", phones.toE164("US", "(201) 555-0101")) // demo seed number
        assertEquals("+919876543210", phones.toE164("US", "+91 98765 43210")) // an explicit +code wins
        listOf("", "12345", "abc").forEach { assertNull(it, phones.toE164("IN", it)) }
    }

    @Test
    fun `countries have dial codes, names and flags`() {
        val india = phones.country("IN")
        assertEquals(91, india.dialCode)
        assertEquals("🇮🇳", india.flag) // 🇮🇳
        assertTrue(phones.countries().size > 200)
        assertEquals("+91 98765 43210", phones.format("+919876543210"))
    }

    @Test
    fun `auth errors map to specific messages`() {
        fun api(code: String, details: Map<String, String> = emptyMap()) = AppError.Api(400, code, code, details)
        assertEquals(UiMessage.Resource(R.string.error_no_linked_account), api("NO_LINKED_ACCOUNT").toAuthMessage())
        assertEquals(UiMessage.Plural(R.plurals.error_otp_invalid_attempts, 2), api("OTP_INVALID", mapOf("attemptsLeft" to "2")).toAuthMessage())
        assertEquals(UiMessage.Resource(R.string.error_otp_invalid), api("OTP_INVALID").toAuthMessage())
        assertEquals(30, api("OTP_RATE_LIMITED", mapOf("retryAfter" to "30")).retryAfterSeconds())
        assertEquals(UiMessage.Resource(R.string.error_phone_invalid), api("VALIDATION_ERROR", mapOf("phone" to "x")).toAuthMessage())
        assertEquals("123456", sanitizeCode(" 12-34 56 789"))
    }
}
