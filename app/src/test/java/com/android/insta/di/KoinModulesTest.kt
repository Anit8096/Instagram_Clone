package com.android.insta.di

import android.content.Context
import androidx.work.WorkerParameters
import io.ktor.client.HttpClientConfig
import org.junit.Test
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.dsl.module
import org.koin.test.verify.verify

/** Fails if any constructor-declared dependency in the app graph has no definition. */
class KoinModulesTest {
    @OptIn(KoinExperimentalAPI::class)
    @Test
    fun `app module graph is complete`() {
        module { includes(appModules) }.verify(
            // Supplied by Android (androidContext), by literals in definitions, or built inside createHttpClient.
            extraTypes = listOf(Context::class, Boolean::class, HttpClientConfig::class, WorkerParameters::class, Function0::class, Function1::class, String::class),
        )
    }
}
