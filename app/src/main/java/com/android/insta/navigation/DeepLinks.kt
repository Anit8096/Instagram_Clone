package com.android.insta.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI

/**
 * `insta://` deep links (from push notifications or `adb shell am start -d`):
 * - `insta://post/{id}` → post detail
 * - `insta://user/{username}` → profile
 * - `insta://chat/{username}` → conversation (Inbox underneath, so back lands in the inbox)
 * - `insta://activity` → Activity tab
 */
object DeepLinks {
    private val USERNAME = Regex("[a-z0-9._]{3,30}")
    private val UUID = Regex("[0-9a-fA-F-]{36}")

    fun parse(link: String): NavKey? {
        val uri = runCatching { URI(link) }.getOrNull() ?: return null
        if (uri.scheme != "insta") return null
        val arg = uri.path.orEmpty().trim('/').takeIf { '/' !in it }.orEmpty()
        return when (uri.host) {
            "post" -> arg.takeIf { UUID.matches(it) }?.let { DetailRoute.PostDetail(it) }
            "user" -> arg.takeIf { USERNAME.matches(it) }?.let { DetailRoute.UserProfile(it) }
            "chat" -> arg.takeIf { USERNAME.matches(it) }?.let { DetailRoute.Thread(it) }
            "activity" -> MainRoute.Notifications
            else -> null
        }
    }
}

/**
 * Hand-off from the Activity (intents) to the signed-in shell (back stacks). Held until consumed, so a link that
 * arrives during splash or sign-in is still opened once the shell is up.
 */
class PendingDeepLinks {
    private val _pending = MutableStateFlow<NavKey?>(null)
    val pending: StateFlow<NavKey?> = _pending.asStateFlow()

    fun submit(route: NavKey) {
        _pending.value = route
    }

    fun consume() {
        _pending.value = null
    }
}
