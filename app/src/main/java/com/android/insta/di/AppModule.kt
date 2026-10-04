package com.android.insta.di

import com.android.insta.BuildConfig
import com.android.insta.core.database.AppDatabase
import com.android.insta.core.media.AndroidImageCompressor
import com.android.insta.core.media.ImageCompressor
import com.android.insta.core.network.UrlResolver
import com.android.insta.core.network.createHttpClient
import com.android.insta.core.session.DataStoreSessionStore
import com.android.insta.core.session.KeystoreTokenCipher
import com.android.insta.core.session.SessionManager
import com.android.insta.core.session.SessionStore
import com.android.insta.core.session.TokenCipher
import com.android.insta.core.session.sessionDataStore
import com.android.insta.feature.auth.data.AuthApi
import com.android.insta.feature.auth.data.AuthRepository
import com.android.insta.feature.auth.data.CredentialManagerGoogleSignInClient
import com.android.insta.feature.auth.data.DefaultAuthRepository
import com.android.insta.feature.auth.data.GoogleSignInClient
import com.android.insta.feature.auth.data.UserDataCleaner
import com.android.insta.feature.auth.ui.LoginViewModel
import com.android.insta.feature.auth.ui.RegisterViewModel
import com.android.insta.feature.chat.data.ChatApi
import com.android.insta.feature.chat.data.RealtimeClient
import com.android.insta.feature.chat.ui.InboxViewModel
import com.android.insta.feature.chat.ui.ThreadViewModel
import com.android.insta.feature.engagement.data.ActionQueue
import com.android.insta.feature.engagement.data.ActionSyncWorker
import com.android.insta.feature.engagement.data.EngagementApi
import com.android.insta.feature.engagement.data.SyncScheduler
import com.android.insta.feature.engagement.data.WorkManagerSyncScheduler
import com.android.insta.feature.engagement.ui.CommentsViewModel
import com.android.insta.feature.explore.ui.ExploreViewModel
import com.android.insta.feature.explore.ui.FollowListViewModel
import com.android.insta.feature.feed.data.DefaultFeedRepository
import com.android.insta.feature.feed.data.FeedRepository
import com.android.insta.feature.feed.ui.FeedViewModel
import com.android.insta.feature.social.data.DefaultSocialRepository
import com.android.insta.feature.social.data.SocialApi
import com.android.insta.feature.social.data.SocialRepository
import com.android.insta.feature.post.data.DefaultPostRepository
import com.android.insta.feature.post.data.PostApi
import com.android.insta.feature.post.data.PostRepository
import com.android.insta.feature.post.data.PostUploadWorker
import com.android.insta.feature.post.data.UploadScheduler
import com.android.insta.feature.post.data.WorkManagerUploadScheduler
import com.android.insta.feature.post.ui.CreatePostViewModel
import com.android.insta.feature.post.ui.PostDetailViewModel
import com.android.insta.feature.post.ui.UploadsViewModel
import com.android.insta.feature.profile.data.DefaultProfileRepository
import com.android.insta.feature.profile.data.ProfileApi
import com.android.insta.feature.profile.data.ProfileRepository
import com.android.insta.feature.profile.ui.EditProfileViewModel
import com.android.insta.feature.profile.ui.ProfileViewModel
import com.android.insta.feature.notifications.data.ActivityBadge
import com.android.insta.feature.notifications.data.NotificationsApi
import com.android.insta.feature.notifications.data.NotificationsRepository
import com.android.insta.feature.notifications.data.PushRegistrar
import com.android.insta.feature.notifications.data.PushTokens
import com.android.insta.feature.notifications.push.FirebasePushTokens
import com.android.insta.feature.notifications.push.SystemNotifier
import com.android.insta.feature.notifications.ui.ActivityViewModel
import com.android.insta.navigation.PendingDeepLinks
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okio.Path.Companion.toOkioPath
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.dsl.workerOf
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.module

val APP_SCOPE = named("appScope")

val coreModule = module {
    single { Json { ignoreUnknownKeys = true; explicitNulls = false } }
    single(APP_SCOPE) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    single<TokenCipher> { KeystoreTokenCipher() }
    single<SessionStore> { DataStoreSessionStore(androidContext().sessionDataStore, get(), get()) }
    single { SessionManager(get(), get(APP_SCOPE)) }
    single { UrlResolver(BuildConfig.API_BASE_URL) }

    // Separate definition so tests can swap in Ktor's MockEngine.
    single<HttpClientEngine> { OkHttp.create() }
    single {
        createHttpClient(
            engine = get(),
            baseUrl = BuildConfig.API_BASE_URL,
            sessionStore = get(),
            json = get(),
            enableLogging = BuildConfig.DEBUG,
            // Server-side expiry: same cleanup as an explicit logout. Resolved lazily (the cleaner needs this client).
            onSessionExpired = {
                get<UserDataCleaner>().clear()
                get<SessionStore>().clear()
            },
        )
    }

    single { AppDatabase.create(androidContext()) }
    single { get<AppDatabase>().postDraftDao() }
    single<ImageCompressor> { AndroidImageCompressor(androidContext()) }

    // Images load through the same HttpClient; 250 MB disk cache for thumbnails and photos.
    single {
        ImageLoader.Builder(androidContext())
            .components { add(KtorNetworkFetcherFactory(httpClient = { get() })) }
            .diskCache {
                DiskCache.Builder()
                    .directory(androidContext().cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(250L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
    }
}

val authModule = module {
    single { AuthApi(get()) }
    single<GoogleSignInClient> { CredentialManagerGoogleSignInClient(androidContext(), BuildConfig.GOOGLE_SERVER_CLIENT_ID) }
    single {
        UserDataCleaner {
            // Stop this device getting the old account's pushes.
            get<PushRegistrar>().unregister()
            get<PostRepository>().clearDrafts()
            get<FeedRepository>().clearCache()
            get<WorkManagerSyncScheduler>().cancel()
            get<ActionQueue>().clear()
        }
    }
    single<AuthRepository> { DefaultAuthRepository(get(), get(), get(), get()) }
    viewModel { LoginViewModel(get(), get<GoogleSignInClient>().isConfigured) }
    viewModelOf(::RegisterViewModel)
}

val postModule = module {
    single { PostApi(get()) }
    single<UploadScheduler> { WorkManagerUploadScheduler(androidContext()) }
    single<PostRepository> { DefaultPostRepository(get(), get(), get(), get(), get()) }
    workerOf(::PostUploadWorker)
    viewModelOf(::CreatePostViewModel)
    viewModelOf(::UploadsViewModel)
    viewModel { params -> PostDetailViewModel(params.get(), get(), get(), get()) }
}

val profileModule = module {
    single { ProfileApi(get()) }
    single<ProfileRepository> { DefaultProfileRepository(get(), get(), get(), get(), get()) }
    viewModel { params -> ProfileViewModel(params.get(), get(), get(), get(), get()) }
    viewModelOf(::EditProfileViewModel)
}

val socialModule = module {
    single { SocialApi(get()) }
    single<SocialRepository> { DefaultSocialRepository(get(), get()) }
    single<FeedRepository> { DefaultFeedRepository(get(), get(), get(), pendingLikes = { get<ActionQueue>().pendingLikeOverrides() }) }
    viewModelOf(::FeedViewModel)
    viewModelOf(::ExploreViewModel)
    viewModel { params -> FollowListViewModel(params.get(), params.get(), get(), get()) }
}

val engagementModule = module {
    single { EngagementApi(get()) }
    single { WorkManagerSyncScheduler(androidContext()) }
    single<SyncScheduler> { SyncScheduler { get<WorkManagerSyncScheduler>().schedule() } }
    single { ActionQueue(get(), get(), get(), get(), chat = get()) }
    workerOf(::ActionSyncWorker)
    viewModel { params -> CommentsViewModel(params.get(), get(), get(), get()) }
}

val chatModule = module {
    single { ChatApi(get()) }
    single { RealtimeClient(get(), get(), BuildConfig.API_BASE_URL.trimEnd('/').replaceFirst("http", "ws") + "/api/v1/ws") }
    viewModelOf(::InboxViewModel)
    viewModel { params -> ThreadViewModel(params.get(), get(), get(), get(), get()) }
}

val notificationsModule = module {
    single { NotificationsApi(get()) }
    single { NotificationsRepository(get(), get()) }
    single { ActivityBadge(get(), get()) }
    single<PushTokens> { FirebasePushTokens(androidContext()) }
    single { PushRegistrar(get(), get()) }
    single { SystemNotifier(androidContext()) }
    single { PendingDeepLinks() }
    viewModelOf(::ActivityViewModel)
}

val appModules = listOf(coreModule, authModule, postModule, profileModule, socialModule, engagementModule, chatModule, notificationsModule)
