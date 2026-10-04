package com.android.insta.testutil

import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.android.insta.core.database.AppDatabase
import com.android.insta.core.network.UrlResolver
import com.android.insta.core.network.createHttpClient
import com.android.insta.core.session.Session
import com.android.insta.feature.engagement.data.ActionQueue
import com.android.insta.feature.engagement.data.EngagementApi
import com.android.insta.feature.engagement.data.SyncScheduler
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json

/** Real Room in memory (needs Robolectric). */
fun inMemoryDb(): AppDatabase =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .setDriver(AndroidSQLiteDriver())
        .build()

fun testEngagementApi(engine: HttpClientEngine = MockEngine { respond("", HttpStatusCode.ServiceUnavailable) }) = EngagementApi(
    createHttpClient(engine, "http://test", FakeSessionStore(Session("a", "r", TEST_USER)), Json { ignoreUnknownKeys = true; explicitNulls = false }, false),
)

fun testActionQueue(db: AppDatabase = inMemoryDb(), api: EngagementApi = testEngagementApi(), scheduler: SyncScheduler = SyncScheduler { }) =
    ActionQueue(db, api, UrlResolver("http://test"), scheduler, now = { System.nanoTime() })
