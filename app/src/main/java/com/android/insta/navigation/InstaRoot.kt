package com.android.insta.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import com.android.insta.feature.notifications.data.ActivityBadge
import com.android.insta.feature.notifications.ui.ActivityScreen
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.android.insta.R
import com.android.insta.core.session.SessionManager
import com.android.insta.core.session.SessionState
import com.android.insta.feature.auth.ui.LoginScreen
import com.android.insta.feature.auth.ui.RegisterScreen
import com.android.insta.feature.engagement.ui.CommentsScreen
import com.android.insta.feature.chat.ui.InboxScreen
import com.android.insta.feature.chat.ui.ThreadScreen
import com.android.insta.feature.explore.ui.ExploreScreen
import com.android.insta.feature.explore.ui.FollowListScreen
import com.android.insta.feature.feed.ui.FeedScreen
import com.android.insta.feature.post.ui.CreatePostScreen
import com.android.insta.feature.post.ui.PostDetailScreen
import com.android.insta.feature.post.ui.UploadBanner
import com.android.insta.feature.post.ui.UploadsViewModel
import com.android.insta.feature.profile.ui.EditProfileScreen
import com.android.insta.feature.profile.ui.ProfileScreen
import com.android.insta.feature.settings.ui.SettingsScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * Conditional navigation at the root: the session decides which flow is shown. Signing in, signing
 * out and server-side session expiry all flow through [SessionManager.state], so no screen needs
 * to navigate between the two flows itself.
 */
@Composable
fun InstaRoot(sessionManager: SessionManager = koinInject()) {
    val session by sessionManager.state.collectAsStateWithLifecycle()
    when (val current = session) {
        SessionState.Loading -> Unit // The splash screen stays up until the session is read.
        SessionState.LoggedOut -> AuthFlow()
        // Keyed by user so a different account never sees the previous one's tabs or ViewModels.
        is SessionState.LoggedIn -> key(current.user.id) { MainShell(current.user.username) }
    }
}

@Composable
private fun AuthFlow() {
    val backStack = rememberNavBackStack(AuthRoute.Login)
    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
        entryProvider = entryProvider {
            entry<AuthRoute.Login> {
                LoginScreen(onNavigateToRegister = { backStack.add(AuthRoute.Register) })
            }
            entry<AuthRoute.Register> {
                RegisterScreen(onNavigateToLogin = { backStack.removeLastOrNull() })
            }
        },
    )
}

private data class TopLevelDestination(val icon: ImageVector, val label: Int)

private val TOP_LEVEL_DESTINATIONS: Map<NavKey, TopLevelDestination> = linkedMapOf(
    MainRoute.Feed to TopLevelDestination(Icons.Filled.Home, R.string.tab_feed),
    MainRoute.Explore to TopLevelDestination(Icons.Filled.Search, R.string.tab_explore),
    MainRoute.Create to TopLevelDestination(Icons.Filled.AddCircle, R.string.tab_create),
    MainRoute.Notifications to TopLevelDestination(Icons.Filled.Notifications, R.string.tab_notifications),
    MainRoute.Profile to TopLevelDestination(Icons.Filled.AccountCircle, R.string.tab_profile),
)

/** Signed-in shell: bottom bar on phones, navigation rail on wider windows. */
@Composable
private fun MainShell(
    myUsername: String,
    uploadsViewModel: UploadsViewModel = koinViewModel(),
    activityBadge: ActivityBadge = koinInject(),
    deepLinks: PendingDeepLinks = koinInject(),
) {
    val navigationState = rememberNavigationState(MainRoute.Feed, TOP_LEVEL_DESTINATIONS.keys)
    val navigator = remember(navigationState) { Navigator(navigationState) }
    val uploads by uploadsViewModel.uploads.collectAsStateWithLifecycle()
    val unreadActivity by activityBadge.unread.collectAsStateWithLifecycle()

    // Links from notification taps (or `am start -d insta://…`), applied once the shell is showing.
    val pendingLink by deepLinks.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pendingLink) {
        val link = pendingLink ?: return@LaunchedEffect
        val route = if (link is DetailRoute.UserProfile && link.username == myUsername) MainRoute.Profile else link
        navigator.openDeepLink(route)
        deepLinks.consume()
    }

    val labels = TOP_LEVEL_DESTINATIONS.mapValues { (_, destination) -> stringResource(destination.label) }
    val openPost: (String) -> Unit = { navigator.navigate(DetailRoute.PostDetail(it)) }
    val openCreate: () -> Unit = { navigator.navigate(MainRoute.Create) }
    val openUser: (String) -> Unit = { username ->
        if (username == myUsername) navigator.navigate(MainRoute.Profile) else navigator.navigate(DetailRoute.UserProfile(username))
    }
    val openComments: (String) -> Unit = { navigator.navigate(DetailRoute.Comments(it)) }
    val openFollows: (String, Boolean) -> Unit = { username, followers -> navigator.navigate(DetailRoute.FollowList(username, followers)) }
    val entryProvider = entryProvider<NavKey> {
        entry<MainRoute.Feed> {
            FeedScreen(onAuthorClick = openUser, onPhotoClick = openPost, onFindPeople = { navigator.navigate(MainRoute.Explore) }, onCommentsClick = openComments,
                onMessagesClick = { navigator.navigate(DetailRoute.Inbox) })
        }
        entry<MainRoute.Explore> {
            ExploreScreen(onUserClick = openUser, onPostClick = openPost)
        }
        entry<DetailRoute.Inbox> {
            InboxScreen(onBack = { navigator.goBack() }, onOpenThread = { navigator.navigate(DetailRoute.Thread(it)) })
        }
        entry<DetailRoute.Thread> { key ->
            ThreadScreen(key.username, onBack = { navigator.goBack() })
        }
        entry<DetailRoute.Comments> { key ->
            CommentsScreen(key.postId, onBack = { navigator.goBack() }, onUserClick = openUser)
        }
        entry<DetailRoute.FollowList> { key ->
            FollowListScreen(key.username, key.followers, onBack = { navigator.goBack() }, onUserClick = openUser)
        }
        entry<MainRoute.Create> {
            CreatePostScreen(onShared = { navigator.navigate(MainRoute.Profile) })
        }
        entry<MainRoute.Notifications> {
            ActivityScreen(onPostClick = openPost, onCommentsClick = openComments, onUserClick = openUser)
        }
        entry<MainRoute.Profile> {
            ProfileScreen(
                username = myUsername,
                onPostClick = openPost,
                onEditProfile = { navigator.navigate(DetailRoute.EditProfile) },
                onCreatePost = openCreate,
                onFollowsClick = { followers -> openFollows(myUsername, followers) },
                onSettingsClick = { navigator.navigate(DetailRoute.Settings) },
            )
        }
        entry<DetailRoute.Settings> {
            SettingsScreen(onBack = { navigator.goBack() })
        }
        entry<DetailRoute.UserProfile> { key ->
            ProfileScreen(
                username = key.username,
                onPostClick = openPost,
                onEditProfile = { navigator.navigate(DetailRoute.EditProfile) },
                onCreatePost = openCreate,
                onFollowsClick = { followers -> openFollows(key.username, followers) },
                onMessageClick = { navigator.navigate(DetailRoute.Thread(it)) },
                onBack = { navigator.goBack() },
            )
        }
        entry<DetailRoute.PostDetail> { key ->
            PostDetailScreen(
                postId = key.postId,
                onBack = { navigator.goBack() },
                onAuthorClick = openUser,
                onCommentsClick = openComments,
            )
        }
        entry<DetailRoute.EditProfile> {
            EditProfileScreen(onDone = { navigator.goBack() })
        }
    }

    val badgeDescription = pluralStringResource(R.plurals.cd_unread_activity, unreadActivity.toInt(), unreadActivity.toInt())
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            TOP_LEVEL_DESTINATIONS.forEach { (route, destination) ->
                val label = labels.getValue(route)
                val badged = route == MainRoute.Notifications && unreadActivity > 0
                item(
                    // Navigation items clear their icon's semantics, so the badge is announced as the item's state.
                    modifier = if (badged) Modifier.semantics { stateDescription = badgeDescription } else Modifier,
                    selected = route == navigationState.topLevelRoute,
                    onClick = { navigator.navigate(route) },
                    icon = {
                        if (badged) {
                            BadgedBox(badge = { Badge() }) {
                                Icon(destination.icon, contentDescription = label)
                            }
                        } else {
                            Icon(destination.icon, contentDescription = label)
                        }
                    },
                    label = { Text(label) },
                )
            }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            NavDisplay(
                entries = navigationState.toDecoratedEntries(entryProvider),
                onBack = { navigator.goBack() },
            )
            // Background uploads float above the navigation bar, like a persistent snackbar.
            if (uploads.isNotEmpty()) {
                val failed = uploads.firstOrNull { it.failed }
                UploadBanner(
                    uploading = uploads.count { !it.failed },
                    failedCaption = failed?.caption,
                    failedMessage = failed?.error,
                    onRetry = { failed?.let { uploadsViewModel.retry(it.id) } },
                    onDiscard = { failed?.let { uploadsViewModel.discard(it.id) } },
                    modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
                )
            }
        }
    }
}
