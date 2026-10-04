package com.android.insta.server.notifications

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File

/**
 * A data-only push. The app builds the system notification itself (channel, deep link, and suppression while the
 * matching screen is open), so every key here is a plain string.
 */
data class PushMessage(val title: String, val body: String, val link: String, val tag: String, val type: String) {
    fun toData(): Map<String, String> = mapOf("title" to title, "body" to body, "link" to link, "tag" to tag, "type" to type)
}

interface PushSender {
    /** Sends [message] to every token and returns the tokens the push service reported as no longer valid. */
    suspend fun send(tokens: List<String>, message: PushMessage): Set<String>
}

/** Used when no Firebase credentials are configured, so the stack runs without a Firebase project. */
object NoopPushSender : PushSender {
    override suspend fun send(tokens: List<String>, message: PushMessage): Set<String> = emptySet()
}

/** Firebase Cloud Messaging through the Admin SDK; credentials come from a mounted service-account file. */
class FcmPushSender(credentialsFile: String) : PushSender {
    private val log = LoggerFactory.getLogger(FcmPushSender::class.java)

    private val messaging: FirebaseMessaging = run {
        val app = FirebaseApp.getApps().firstOrNull { it.name == APP_NAME } ?: FirebaseApp.initializeApp(
            FirebaseOptions.builder()
                .setCredentials(File(credentialsFile).inputStream().use(GoogleCredentials::fromStream))
                .build(),
            APP_NAME,
        )
        FirebaseMessaging.getInstance(app)
    }

    // Registration tokens are deprecated in favour of Firebase Installation IDs but still co-supported; the app
    // registers tokens, so both sides move together.
    @Suppress("DEPRECATION")
    override suspend fun send(tokens: List<String>, message: PushMessage): Set<String> = withContext(Dispatchers.IO) {
        tokens.chunked(MAX_TOKENS_PER_CALL).flatMap { chunk ->
            val batch = messaging.sendEach(
                chunk.map { token ->
                    Message.builder()
                        .setToken(token)
                        .putAllData(message.toData())
                        // High priority so data messages wake the app promptly in Doze; they still show as normal notifications.
                        .setAndroidConfig(AndroidConfig.builder().setPriority(AndroidConfig.Priority.HIGH).build())
                        .build()
                },
            )
            batch.responses.mapIndexedNotNull { i, response ->
                val code = response.exception?.messagingErrorCode
                if (!response.isSuccessful) log.warn("FCM send failed: {}", code ?: response.exception?.message)
                chunk[i].takeIf { code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT }
            }
        }.toSet()
    }

    private companion object {
        const val APP_NAME = "insta-push"
        const val MAX_TOKENS_PER_CALL = 500
    }
}
