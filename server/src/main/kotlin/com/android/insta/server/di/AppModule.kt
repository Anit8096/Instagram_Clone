package com.android.insta.server.di

import com.android.insta.server.auth.AuthService
import com.android.insta.server.auth.GoogleTokenVerifier
import com.android.insta.server.auth.JwksGoogleTokenVerifier
import com.android.insta.server.auth.LogSmsSender
import com.android.insta.server.auth.OtpService
import com.android.insta.server.auth.SmsSender
import com.android.insta.server.auth.RefreshTokenRepository
import com.android.insta.server.auth.TokenService
import com.android.insta.server.config.AppConfig
import com.android.insta.server.jobs.JobRegistration
import com.android.insta.server.jobs.JobRepository
import com.android.insta.server.jobs.JobRunner
import com.android.insta.server.jobs.maintenanceJob
import com.android.insta.server.redis.Cache
import com.android.insta.server.redis.FixedWindowRateLimiter
import com.android.insta.server.redis.OtpSendThrottle
import com.android.insta.server.redis.Redis
import com.android.insta.server.redis.RedisCache
import com.android.insta.server.media.ImageProcessor
import com.android.insta.server.media.LocalDiskMediaStorage
import com.android.insta.server.media.MediaRepository
import com.android.insta.server.media.MediaService
import com.android.insta.server.media.MediaStorage
import com.android.insta.server.posts.PostRepository
import com.android.insta.server.posts.PostService
import com.android.insta.server.posts.EngagementService
import com.android.insta.server.chat.ChatService
import com.android.insta.server.chat.ConnectionRegistry
import com.android.insta.server.notifications.FcmPushSender
import com.android.insta.server.notifications.NoopPushSender
import com.android.insta.server.notifications.NotificationService
import com.android.insta.server.notifications.PushSender
import com.android.insta.server.social.SocialService
import com.android.insta.server.users.AccountService
import com.android.insta.server.users.ProfileService
import com.android.insta.server.users.UserRepository
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.core.module.dsl.onClose
import org.koin.core.module.dsl.withOptions
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.time.Clock

fun appModule(config: AppConfig, database: Database) = module {
    single { config }
    single { database }
    single<Clock> { Clock.systemUTC() }

    // Redis: rate limits, cache and the job queue's transport. Postgres stays the source of truth.
    single { Redis(config.redis) } withOptions { onClose { it?.close() } }
    single<Cache> { RedisCache(get()) }
    single { FixedWindowRateLimiter(get(), get()) }
    single { OtpSendThrottle(get(), get()) }
    single { JobRepository(get()) }
    single(named("maintenance")) { maintenanceJob(get(), get(), get()) }
    // Collects every JobRegistration definition, including ones tests add.
    single { JobRunner(get(), get(), get(), config.jobs, get(), getAll<JobRegistration>()) } withOptions { onClose { it?.close() } }

    single { UserRepository(get()) }
    single { RefreshTokenRepository(get()) }

    // Codes are logged locally; a real SMS provider replaces this binding.
    single<SmsSender> { LogSmsSender() }
    single { OtpService(get(), get(), config.otp, config.jwt.secret, get(), get()) }
    single { TokenService(config.jwt, get()) }
    single<GoogleTokenVerifier> { JwksGoogleTokenVerifier(config.google) }
    single { AuthService(get(), get(), get(), get(), get(), get()) }

    single<MediaStorage> { LocalDiskMediaStorage(config.mediaRoot) }
    single { ImageProcessor() }
    single { MediaRepository(get()) }
    single { MediaService(get(), get(), get()) }
    single { PostRepository(get()) }
    single { PostService(get(), get(), get(), get(), get(), get()) }
    single { ProfileService(get(), get(), get(), get(), get()) }
    single { AccountService(get(), get(), get(), get(), get(), get()) }
    single { SocialService(get(), get(), get(), get(), get()) }
    single { EngagementService(get(), get(), get()) }
    single { ConnectionRegistry() }
    single { ChatService(get(), get(), get(), get(), get()) }

    // Push is optional: without a Firebase service-account file the stack runs with in-app notifications only.
    single<PushSender> { config.firebaseCredentialsFile?.let(::FcmPushSender) ?: NoopPushSender }
    single { NotificationService(get(), get(), get(), get()) } withOptions { onClose { it?.close() } }
}
