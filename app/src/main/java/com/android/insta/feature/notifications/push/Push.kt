package com.android.insta.feature.notifications.push

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.android.insta.MainActivity
import com.android.insta.R
import com.android.insta.di.APP_SCOPE
import com.android.insta.feature.notifications.data.PushRegistrar
import com.android.insta.feature.notifications.data.PushTokens
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.koin.android.ext.android.inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A data message from the server (see the server's `PushMessage`). */
data class PushPayload(val title: String, val body: String, val link: String, val tag: String, val type: String) {
    val isMessage: Boolean get() = type == "message"

    companion object {
        /** Null for malformed payloads (missing keys or a link that isn't ours). */
        fun from(data: Map<String, String>): PushPayload? {
            val title = data["title"]?.takeIf { it.isNotBlank() } ?: return null
            val link = data["link"]?.takeIf { it.startsWith("insta://") } ?: return null
            return PushPayload(title, data["body"].orEmpty(), link, data["tag"] ?: link, data["type"].orEmpty())
        }
    }
}

/**
 * Firebase-backed tokens; inert when the app was built without `google-services.json`.
 *
 * FCM is moving from registration tokens to Firebase Installation IDs (`register()` / `onRegistered()`). Both are
 * co-supported, and the server's Admin SDK sends address registration tokens, so this stays on tokens until the
 * server side moves too.
 */
@Suppress("DEPRECATION")
class FirebasePushTokens(private val context: Context) : PushTokens {
    private val available: Boolean get() = FirebaseApp.getApps(context).isNotEmpty()

    override suspend fun current(): String? = if (available) FirebaseMessaging.getInstance().token.await() else null

    override suspend fun delete() {
        if (available) FirebaseMessaging.getInstance().deleteToken().await()
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnCompleteListener { task ->
            if (task.isSuccessful) cont.resume(task.result) else cont.resumeWithException(task.exception ?: IllegalStateException("Task failed"))
        }
    }
}

/** System notifications: two channels so people can mute activity but keep messages (or the reverse). */
class SystemNotifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        manager.createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(CHANNEL_MESSAGES, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName(context.getString(R.string.channel_messages))
                    .setDescription(context.getString(R.string.channel_messages_description))
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_ACTIVITY, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName(context.getString(R.string.channel_activity))
                    .setDescription(context.getString(R.string.channel_activity_description))
                    .build(),
            ),
        )
    }

    fun canPost(): Boolean =
        manager.areNotificationsEnabled() && (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    fun show(push: PushPayload) {
        if (!canPost()) return
        // Tapping opens MainActivity with the deep link; singleTop delivers it to onNewIntent when already running.
        val intent = Intent(Intent.ACTION_VIEW, push.link.toUri(), context, MainActivity::class.java)
        val contentIntent = PendingIntent.getActivity(context, push.tag.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, if (push.isMessage) CHANNEL_MESSAGES else CHANNEL_ACTIVITY)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(push.title)
            .setContentText(push.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(push.body))
            .setCategory(if (push.isMessage) NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_SOCIAL)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        // Same tag replaces: a burst of likes on one post or messages from one person stays a single notification.
        try {
            manager.notify(push.tag, 0, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post.
        }
    }

    companion object {
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_ACTIVITY = "activity"
    }
}

/** Receives FCM data messages (the server only pushes when the app has no live socket) and token rotations. */
class InstaMessagingService : FirebaseMessagingService() {
    private val notifier: SystemNotifier by inject()
    private val registrar: PushRegistrar by inject()
    private val appScope: CoroutineScope by inject(APP_SCOPE)

    @Deprecated("Registration tokens are superseded by installation IDs; see FirebasePushTokens.")
    override fun onNewToken(token: String) {
        appScope.launch { registrar.onNewToken(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        PushPayload.from(message.data)?.let(notifier::show)
    }
}
