package com.android.insta.server.auth

import com.android.insta.server.common.ApiException
import com.android.insta.server.common.ValidationException
import com.android.insta.server.config.OtpConfig
import com.android.insta.server.db.OtpChallenges
import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.toJavaDuration
import kotlin.uuid.Uuid

/** Validates and normalises phone numbers to E.164 (`+919876543210`). Clients send E.164 already. */
object PhoneNumbers {
    private val util = PhoneNumberUtil.getInstance()

    fun normalize(raw: String): String {
        val number = try {
            util.parse(raw.trim(), null) // null region: the number must carry its own +country code
        } catch (e: NumberParseException) {
            throw invalid()
        }
        if (!util.isValidNumber(number)) throw invalid()
        return util.format(number, PhoneNumberUtil.PhoneNumberFormat.E164)
    }

    private fun invalid() = ValidationException(mapOf("phone" to "Enter a valid phone number with its country code"))
}

enum class OtpPurpose(val value: String) { LOGIN("login"), ONBOARDING("onboarding"), CHANGE_PHONE("change_phone"), DELETE_ACCOUNT("delete_account") }

/** Who a code is for: an existing account, or a Google identity that is still onboarding. */
sealed interface OtpOwner {
    data class User(val id: Uuid) : OtpOwner
    data class Onboarding(val googleSubject: String) : OtpOwner
}

@Serializable
data class OtpChallengeDto(
    val challengeId: String,
    /** Masked, e.g. "+91 ••••• •3210", for "We sent a code to …". */
    val sentTo: String,
    val expiresIn: Long,
    val resendIn: Long,
    /** Only with OTP_DEV_ECHO=true (local demos and tests). */
    val devCode: String? = null,
)

/** Delivers codes. Local development logs them; a real SMS provider plugs in here. */
fun interface SmsSender {
    suspend fun send(phone: String, message: String)
}

class LogSmsSender : SmsSender {
    private val log = LoggerFactory.getLogger("sms")
    override suspend fun send(phone: String, message: String) = log.info("SMS to {}: {}", phone, message)
}

/**
 * One-time codes: 6 random digits, stored only as an HMAC (keyed with a server secret), valid for [OtpConfig.codeTtl],
 * single use, at most [OtpConfig.maxAttempts] guesses. Sends per number are throttled by a cooldown and an hourly cap.
 * Each code is bound to a purpose and an owner, so a sign-in code can't confirm an account deletion.
 */
class OtpService(
    private val db: Database,
    private val sms: SmsSender,
    private val config: OtpConfig,
    secret: String,
    private val clock: Clock,
) {
    private val key = SecretKeySpec(secret.toByteArray(), "HmacSHA256")
    private val random = SecureRandom()

    suspend fun request(phone: String, purpose: OtpPurpose, owner: OtpOwner): OtpChallengeDto {
        val now = now()
        val code = (0 until 6).joinToString("") { random.nextInt(10).toString() }
        val id = Uuid.random()
        suspendTransaction(db) {
            val recent = OtpChallenges.selectAll()
                .where { (OtpChallenges.phone eq phone) and (OtpChallenges.createdAt greater now.minusHours(1)) }
                .orderBy(OtpChallenges.createdAt to SortOrder.DESC)
                .map { it[OtpChallenges.createdAt] }
            val waitSeconds = recent.firstOrNull()?.let { config.resendCooldown.inWholeSeconds - java.time.Duration.between(it, now).seconds } ?: 0
            if (waitSeconds > 0) throw rateLimited("Wait $waitSeconds s before requesting another code", waitSeconds)
            if (recent.size >= config.maxPerHour) throw rateLimited("Too many codes for this number. Try again later.", 3600)

            OtpChallenges.insert {
                it[OtpChallenges.id] = id
                it[OtpChallenges.phone] = phone
                it[OtpChallenges.purpose] = purpose.value
                it[userId] = (owner as? OtpOwner.User)?.id
                it[onboardingSubject] = (owner as? OtpOwner.Onboarding)?.googleSubject
                it[codeHash] = hash(id, code)
                it[attempts] = 0
                it[expiresAt] = now.plus(config.codeTtl.toJavaDuration())
                it[createdAt] = now
            }
        }
        sms.send(phone, "Your Insta code is $code. It expires in ${config.codeTtl.inWholeMinutes} minutes. Don't share it.")
        return OtpChallengeDto(
            challengeId = id.toString(),
            sentTo = mask(phone),
            expiresIn = config.codeTtl.inWholeSeconds,
            resendIn = config.resendCooldown.inWholeSeconds,
            devCode = code.takeIf { config.devEcho },
        )
    }

    /**
     * Consumes the code and returns the verified phone. Wrong purpose or owner looks like an unknown challenge, so a
     * code can't be replayed into another flow.
     */
    suspend fun verify(challengeId: String, code: String, purpose: OtpPurpose, owner: OtpOwner): String {
        val id = runCatching { Uuid.parse(challengeId) }.getOrNull() ?: throw invalidCode()
        val now = now()
        val (phone, failure) = suspendTransaction(db) {
            val row = OtpChallenges.selectAll().where { OtpChallenges.id eq id }.forUpdate().singleOrNull()
                ?: return@suspendTransaction null to invalidCode()
            val ownerMatches = when (owner) {
                is OtpOwner.User -> row[OtpChallenges.userId] == owner.id
                is OtpOwner.Onboarding -> row[OtpChallenges.onboardingSubject] == owner.googleSubject
            }
            when {
                row[OtpChallenges.purpose] != purpose.value || !ownerMatches -> null to invalidCode()
                row[OtpChallenges.consumedAt] != null -> null to ApiException(HttpStatusCode.BadRequest, "OTP_USED", "This code was already used. Request a new one.")
                row[OtpChallenges.expiresAt] < now -> null to ApiException(HttpStatusCode.BadRequest, "OTP_EXPIRED", "This code has expired. Request a new one.")
                row[OtpChallenges.attempts] >= config.maxAttempts -> null to tooManyAttempts()
                !MessageDigest.isEqual(hash(id, code.trim()).toByteArray(), row[OtpChallenges.codeHash].toByteArray()) -> {
                    OtpChallenges.update({ OtpChallenges.id eq id }) { it[attempts] = attempts + 1 }
                    val left = config.maxAttempts - row[OtpChallenges.attempts] - 1
                    null to if (left <= 0) tooManyAttempts() else ApiException(
                        HttpStatusCode.BadRequest, "OTP_INVALID", "That code isn't right", mapOf("attemptsLeft" to left.toString()),
                    )
                }
                else -> {
                    OtpChallenges.update({ OtpChallenges.id eq id }) { it[consumedAt] = now }
                    row[OtpChallenges.phone] to null
                }
            }
        }
        // Thrown after the transaction commits, so the attempt counter increment is kept.
        if (failure != null) throw failure
        return phone!!
    }

    /** The account a challenge was issued to (phone sign-in), or null for unknown ids and onboarding codes. */
    suspend fun ownerOf(challengeId: String): Uuid? {
        val id = runCatching { Uuid.parse(challengeId) }.getOrNull() ?: return null
        return suspendTransaction(db) {
            OtpChallenges.selectAll().where { OtpChallenges.id eq id }.singleOrNull()?.get(OtpChallenges.userId)
        }
    }

    private fun hash(id: Uuid, code: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(key) }
        return mac.doFinal("$id:$code".toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun now() = OffsetDateTime.now(clock.withZone(ZoneOffset.UTC))

    private fun invalidCode() = ApiException(HttpStatusCode.BadRequest, "OTP_INVALID", "That code isn't right")
    private fun tooManyAttempts() = ApiException(HttpStatusCode.BadRequest, "OTP_TOO_MANY_ATTEMPTS", "Too many wrong codes. Request a new one.")
    private fun rateLimited(message: String, retryAfter: Long) =
        ApiException(HttpStatusCode.TooManyRequests, "OTP_RATE_LIMITED", message, mapOf("retryAfter" to retryAfter.toString()))

    companion object {
        /** "+919876543210" → "+91 •••••• 3210"-style: country code and the last 4 digits only. */
        fun mask(phone: String): String {
            val util = PhoneNumberUtil.getInstance()
            val countryCode = runCatching { util.parse(phone, null).countryCode }.getOrNull()
            val prefix = countryCode?.let { "+$it" } ?: phone.take(3)
            val hidden = (phone.length - prefix.length - 4).coerceAtLeast(0)
            return "$prefix ${"•".repeat(hidden)}${phone.takeLast(4)}"
        }
    }
}
