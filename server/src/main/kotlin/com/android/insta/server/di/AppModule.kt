package com.android.insta.server.di

import com.android.insta.server.auth.Argon2PasswordHasher
import com.android.insta.server.auth.AuthService
import com.android.insta.server.auth.GoogleTokenVerifier
import com.android.insta.server.auth.JwksGoogleTokenVerifier
import com.android.insta.server.auth.PasswordHasher
import com.android.insta.server.auth.RefreshTokenRepository
import com.android.insta.server.auth.TokenService
import com.android.insta.server.config.AppConfig
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
import com.android.insta.server.social.SocialService
import com.android.insta.server.users.ProfileService
import com.android.insta.server.users.UserRepository
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.dsl.module
import java.time.Clock

fun appModule(config: AppConfig, database: Database) = module {
    single { config }
    single { database }
    single<Clock> { Clock.systemUTC() }

    single { UserRepository(get()) }
    single { RefreshTokenRepository(get()) }

    single<PasswordHasher> { Argon2PasswordHasher() }
    single { TokenService(config.jwt, get()) }
    single<GoogleTokenVerifier> { JwksGoogleTokenVerifier(config.google) }
    single { AuthService(get(), get(), get(), get(), get(), get()) }

    single<MediaStorage> { LocalDiskMediaStorage(config.mediaRoot) }
    single { ImageProcessor() }
    single { MediaRepository(get()) }
    single { MediaService(get(), get(), get()) }
    single { PostRepository(get()) }
    single { PostService(get(), get(), get(), get(), get()) }
    single { ProfileService(get(), get(), get(), get(), get()) }
    single { SocialService(get(), get(), get()) }
    single { EngagementService(get(), get()) }
    single { ConnectionRegistry() }
    single { ChatService(get(), get(), get(), get()) }
}
